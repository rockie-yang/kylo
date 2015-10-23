/**
 * 
 */
package com.thinkbiganalytics.alerts.api.core;

import java.io.Serializable;
import java.net.URI;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.thinkbiganalytics.alerts.api.Alert;
import com.thinkbiganalytics.alerts.api.Alert.ID;
import com.thinkbiganalytics.alerts.api.AlertChangeEvent;
import com.thinkbiganalytics.alerts.api.AlertListener;
import com.thinkbiganalytics.alerts.api.AlertProvider;
import com.thinkbiganalytics.alerts.api.AlertResponder;
import com.thinkbiganalytics.alerts.api.AlertResponse;
import com.thinkbiganalytics.alerts.spi.AlertManager;
import com.thinkbiganalytics.alerts.spi.AlertNotifyReceiver;
import com.thinkbiganalytics.alerts.spi.AlertSource;

/**
 *
 * @author Sean Felten
 */
public class AggregatingAlertProvider implements AlertProvider, AlertNotifyReceiver {
    
    private static final Logger LOG = LoggerFactory.getLogger(AggregatingAlertProvider.class);
    
    private Set<AlertListener> listeners;
    private List<AlertResponder> responders;
    private Map<String, AlertSource> sources;
    private Map<String, AlertManager> managers;
    private Map<SourceAlertID, AlertManager> pendingResponses;
    
    private volatile Executor listenersExecutor;
    private volatile Executor respondersExecutor;
    private volatile DateTime lastAlertsTime = DateTime.now();

    /**
     * 
     */
    public AggregatingAlertProvider() {
        this.listeners = Collections.synchronizedSet(new HashSet<AlertListener>());
        this.responders = Collections.synchronizedList(new ArrayList<AlertResponder>());
        this.sources = Collections.synchronizedMap(new HashMap<String, AlertSource>());
        this.managers = Collections.synchronizedMap(new HashMap<String, AlertManager>());
        this.pendingResponses = Collections.synchronizedMap(new LinkedHashMap<SourceAlertID, AlertManager>());
    }
    
    public void setListenersExecutor(Executor listenersExecutor) {
        synchronized (this) {
            this.listenersExecutor = listenersExecutor;
        }
    }
    
    public void setRespondersExecutor(Executor respondersExecutor) {
        synchronized (this) {
            this.respondersExecutor = respondersExecutor;
        }
    }
    
    
    @Override
    public ID resolve(Serializable value) {
        if (value instanceof String) {
            return SourceAlertID.create((String) value, this.sources);
        } else if (value instanceof SourceAlertID) {
            return (SourceAlertID) value;
        } else {
            throw new IllegalArgumentException("Unrecognized alert ID format: " + value);
        }
    }
    
    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#addListener(com.thinkbiganalytics.alerts.api.AlertListener)
     */
    @Override
    public void addListener(AlertListener listener) {
        this.listeners.add(listener);
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#addResponder(com.thinkbiganalytics.alerts.api.AlertResponder)
     */
    @Override
    public void addResponder(AlertResponder responder) {
        this.responders.add(responder);
    }
    
    public void addAlertSource(AlertSource src) {
        this.sources.put(createSourceId(src), src);
    }
    
    public void addAlertManager(AlertManager mgr) {
        addAlertSource(mgr);
        this.managers.put(createSourceId(mgr), mgr);
        mgr.addReceiver(this);
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#getAlert(com.thinkbiganalytics.alerts.api.Alert.ID)
     */
    @Override
    public Alert getAlert(ID id) {
        SourceAlertID srcId = asSourceAlertId(id);
        AlertSource src = this.sources.get(srcId.sourceId);
        
        if (src != null) {
            Alert alert = src.getAlert(srcId.alertId);
            return new AlertDecorator(srcId, alert);
        } else {
            return null;
        }
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#getAlerts(org.joda.time.DateTime)
     */
    @Override
    public Iterator<? extends Alert> getAlerts(DateTime since) {
        Map<String, AlertSource> srcs = snapshotSources();
        return combineAlerts(since, srcs);
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#getAlerts(com.thinkbiganalytics.alerts.api.Alert.ID)
     */
    @Override
    public Iterator<? extends Alert> getAlerts(ID since) {
        Alert sinceAlert = getAlert(since);
        
        if (sinceAlert != null) {
            DateTime created = sinceAlert.getEvents().get(sinceAlert.getEvents().size() - 1).getChangeTime();
            Map<String, AlertSource> srcs = snapshotSources();
            
            return combineAlerts(created, srcs);
        } else {
            return Collections.<Alert>emptySet().iterator();
        }
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.alerts.api.AlertProvider#respondTo(com.thinkbiganalytics.alerts.api.Alert.ID, com.thinkbiganalytics.alerts.api.AlertResponder)
     */
    @Override
    public void respondTo(ID id, AlertResponder responder) {
        SimpleEntry<Alert, AlertManager> found = findActionableAlert(id);
        
        if (found != null) {
            AlertDecorator decorator = new AlertDecorator(found.getKey(), found.getValue());
            alertChange(decorator, responder, found.getValue());
        }
    }
    
    @Override
    public void alertsAvailable(int count) {
        Executor exec = getListenersExecutor();
        
        LOG.info("Alert available: {}", count);
        
        exec.execute(new Runnable() {
            public void run() {
                DateTime sinceTime = AggregatingAlertProvider.this.lastAlertsTime;
                Map<String, AlertSource> srcList = snapshotSources();
                Iterator<AlertDecorator> combinedAlerts = combineAlerts(sinceTime, srcList);
                
                while (combinedAlerts.hasNext()) {
                    AlertDecorator decorator = combinedAlerts.next();
                    AlertSource src = srcList.get(decorator.id.sourceId);
                    
                    LOG.info("Alert {} received from {}", decorator.getId(), src);
                    
                    notifyListeners(decorator);
                    
                    if (src instanceof AlertManager && decorator.isActionable()) {
                        notifyResponders(decorator, (AlertManager) src);
                    }
                    
                    sinceTime = getCreationTime(decorator);
                }
                
                AggregatingAlertProvider.this.lastAlertsTime = sinceTime;
            }
        });
    }
    
    protected DateTime getCreationTime(AlertDecorator decorator) {
        List<? extends AlertChangeEvent> events = decorator.getEvents();
        // There should always be at least one creation event; the last one in the list
        return events.get(events.size() - 1).getChangeTime();
    }

    /**
     * Generates a unique, internal ID for this source
     */
    protected static String createSourceId(AlertSource src) {
        return Integer.toString(src.hashCode());
    }
    
    protected Executor getListenersExecutor() {
        if (this.listenersExecutor == null) {
            synchronized (this) {
                if (this.listenersExecutor == null) {
                    this.listenersExecutor = Executors.newCachedThreadPool(
                            new ThreadFactoryBuilder().setDaemon(true).build());
                }
            }
        }
        
        return listenersExecutor;
    }

    protected Executor getRespondersExecutor() {
        if (this.respondersExecutor == null) {
            synchronized (this) {
                if (this.respondersExecutor == null) {
                    this.respondersExecutor = Executors.newFixedThreadPool(1);
                }
            }
        }
        
        return respondersExecutor;
    }

    protected Iterator<AlertDecorator> combineAlerts(DateTime since, Map<String, AlertSource> srcs) {
        List<Iterator<AlertDecorator>> iterators = new ArrayList<>();
        
        for (Entry<String, AlertSource> src : srcs.entrySet()) {
            Function<Alert, AlertDecorator> func = decorateAlertFunction(src.getKey(), src.getValue());
            Iterator<? extends Alert> itr = src.getValue().getAlerts(since);
            
            LOG.info("{} alerts available from {} since: {}", itr.hasNext() ? "There are " : "No", src.getValue(), since);
            
            iterators.add(Iterators.transform(itr, func));
        }
        
        return Iterators.concat(iterators.iterator());
    }

    private Function<Alert, AlertDecorator> decorateAlertFunction(final Object srcId, final AlertSource src) {
        return new Function<Alert, AlertDecorator>() {
            @Override
            public AlertDecorator apply(Alert input) {
                return new AlertDecorator(input, src);
            }
        };
    }
    
    private void notifyChanged(AlertDecorator alert, AlertManager manager) {
        notifyListeners(alert);
        notifyResponders(alert, manager);
    }

    protected void notifyListeners(final Alert alert) {
        Executor exec = getListenersExecutor();
        final List<? extends AlertListener> list;
    
        synchronized (this.listeners) {
            list = new ArrayList<>(this.listeners);
        }
        
        exec.execute(new Runnable() {
            @Override
            public void run() {
                for (AlertListener listener : list) {
                    listener.alertChange(alert);
                }
            }
        });
    }

    private void notifyResponders(AlertDecorator alert, AlertManager manager) {
        this.pendingResponses.put(alert.getSourceId(), manager);
        signalResponders();
    }

    private void signalResponders() {
        Executor exec = getRespondersExecutor();
        final Map<SourceAlertID, AlertManager> pending = new HashMap<>();
        
        synchronized (this.pendingResponses) {
            for (Map.Entry<SourceAlertID, AlertManager> entry : this.pendingResponses.entrySet()) {
                pending.put(entry.getKey(), entry.getValue());
            }
        }
        
        LOG.info("Firing changes for alert(s): {}", pending.size());
        
        if (pending.size() > 0) {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    List<AlertResponder> respList = snapshotResponderts();
                    
                    LOG.info("Invoking responders for alerts: {}", respList);

                    for (Map.Entry<SourceAlertID, AlertManager> entry : pending.entrySet()) {
                        for (AlertResponder responder : respList) {
                            AlertManager manager = entry.getValue();
                            Alert alert = manager.getAlert(entry.getKey().alertId);

                            LOG.info("Alert change: {}  from manager: {}  responder: {}", alert, manager, responder);
                            
                            if (alert != null) {
                                AlertDecorator decorator = new AlertDecorator(alert, manager);
                                alertChange(decorator, responder, manager);
                            }
                        }
                    }
                }
            });
        }
    }
    
    protected void alertChange(AlertDecorator alert, AlertResponder responder, AlertManager manager) {
        ManagerAlertResponse resp = new ManagerAlertResponse(alert.getSourceAlert(), manager);
        
        responder.alertChange(alert, resp);
        
        if (resp.resultAlert != null) {
            notifyChanged(resp.resultAlert, resp.manager);
        }
    }
    
    protected List<AlertListener> snapshotListeners() {
        List<AlertListener> listenerList;
        
        synchronized (AggregatingAlertProvider.this.listeners) {
            listenerList = new ArrayList<>(AggregatingAlertProvider.this.listeners);
        }
        return listenerList;
    }
    
    protected List<AlertResponder> snapshotResponderts() {
        List<AlertResponder> respList;
        
        synchronized (AggregatingAlertProvider.this.responders) {
            respList = new ArrayList<>(AggregatingAlertProvider.this.responders);
        }
        return respList;
    }
    
    protected Map<String, AlertSource> snapshotSources() {
        Map<String, AlertSource> srcList;
        
        synchronized (AggregatingAlertProvider.this.sources) {
            srcList = new HashMap<>(AggregatingAlertProvider.this.sources);
        }
        return srcList;
    }

    private SimpleEntry<Alert, AlertManager> findActionableAlert(ID id) {
        SourceAlertID srcId = asSourceAlertId(id);
        AlertManager mgr = this.managers.get(srcId.sourceId);
        Alert alert = mgr.getAlert(srcId.alertId);
        
        if (alert != null && alert.isActionable()) {
            return new SimpleEntry<>(alert, mgr);
        } else {
            return null;
        }
    }
    
    private SourceAlertID asSourceAlertId(ID id) {
        if (id instanceof SourceAlertID) {
            return (SourceAlertID) id;
        } else {
            // Can only happen if the client uses a different ID than was supplied by this provider.
            throw new IllegalArgumentException("Unrecognized sourceAlert ID type: " + id);
        }
    }

    /**
     * Decorates an alert ID with an internal identifier of its source.
     */
    protected static class SourceAlertID implements Alert.ID {
        private static final long serialVersionUID = -3799345314250454959L;

        private final Alert.ID alertId;
        private final String sourceId;
        
        public static SourceAlertID create(String str, Map<String, AlertSource> sources) {
            int sepIdx = str.lastIndexOf(":");
            String alertPart = str.substring(0, sepIdx);
            String srcId = str.substring(sepIdx + 1);
            AlertSource src = sources.get(srcId);
            
            if (src != null) {
                Alert.ID alertId = src.resolve(alertPart);
                return new SourceAlertID(alertId, src);
            } else {
                throw new IllegalArgumentException("Unrecognized alert ID: " + str);
            }
            
        }
        
        public SourceAlertID(ID alertId, AlertSource src) {
            super();
            this.alertId = alertId;
            this.sourceId = createSourceId(src);
        }
        
        @Override
        public String toString() {
            return this.alertId.toString() + ":" + this.sourceId;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (! this.getClass().equals(obj.getClass()))
                return false;
            
            SourceAlertID that = (SourceAlertID) obj;
            
            return Objects.equals(this.alertId, that.alertId) &&
                    Objects.equals(this.sourceId, that.sourceId);
         }
        
        @Override
        public int hashCode() {
            return Objects.hash(getClass(), this.alertId, this.sourceId);
        }
    }
    
    /**
     * Decorates an alert so that with a provider-specific ID that points to the 
     * underlying alert source.
     */
    protected static class AlertDecorator implements Alert {
        
        private final SourceAlertID id;
        private final Alert sourceAlert;

        private AlertDecorator(SourceAlertID id, Alert alert) {
            super();
            this.id = id;
            this.sourceAlert = alert;
        }
        
        public AlertDecorator(Alert srcAlert, AlertSource src) {
            this(new SourceAlertID(srcAlert.getId(), src), srcAlert);
        }

        public Alert getSourceAlert() {
            return this.sourceAlert;
        }
        
        public SourceAlertID getSourceId() {
            return this.id;
        }

        @Override
        public ID getId() {
            return this.id;
        }

        public URI getType() {
            return sourceAlert.getType();
        }

        public String getDescription() {
            return sourceAlert.getDescription();
        }

        public Level getLevel() {
            return sourceAlert.getLevel();
        }

        public AlertSource getSource() {
            return sourceAlert.getSource();
        }

        public boolean isActionable() {
            return sourceAlert.isActionable();
        }

        public List<? extends AlertChangeEvent> getEvents() {
            return sourceAlert.getEvents();
        }

        public <C> C getContent() {
            return sourceAlert.getContent();
        }
    }
    
    
    private class ManagerAlertResponse implements AlertResponse {
        
        private final Alert targetAlert;
        private final AlertManager manager;

        private AlertDecorator resultAlert = null;
        
        public ManagerAlertResponse(Alert alert, AlertManager mgr) {
            this.targetAlert = alert;
            this.manager = mgr;
        }

        @Override
        public <C> void inProgress(C content) {
            changed(this.manager.changeState(this.targetAlert, Alert.State.IN_PROGRESS, content));
        }

        @Override
        public <C> void handle(C content) {
            changed(this.manager.changeState(this.targetAlert, Alert.State.HANDLED, content));
        }

        @Override
        public <C> void unHandle(C content) {
            changed(this.manager.changeState(this.targetAlert, Alert.State.UNHANDLED, content));
        }

        @Override
        public void clear() {
            changed(this.manager.remove(this.targetAlert.getId()));
        }
        
        private void changed(Alert alert) {
            this.resultAlert = new AlertDecorator(alert, this.manager);
            notifyChanged(this.resultAlert, this.manager);
        }
    }
}
