/**
 * 
 */
package com.thinkbiganalytics.nifi.core.api.metadata;

import java.util.Optional;

import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessSession;

import com.thinkbiganalytics.metadata.rest.model.feed.InitializationStatus;

/**
 * Records metadata that will eventually be committed to the metadata store; sometimes only 
 * upon a flow's successful completion.
 * 
 * @author Sean Felten
 */
public interface MetadataRecorder {
    
    FlowFile loadWaterMark(ProcessSession session, 
                           FlowFile ff, 
                           String feedId, 
                           String waterMarkName, 
                           String parameterName, 
                           String defaultValue) throws WaterMarkActiveException;
    
    FlowFile recordWaterMark(ProcessSession session,
                             FlowFile ff,
                             String feedId,
                             String waterMarkName,
                             String parameterName, 
                             String newValue);
    
    FlowFile commitWaterMark(ProcessSession session, FlowFile ff, String feedId, String waterMarkName);
    
    FlowFile commitAllWaterMarks(ProcessSession session, FlowFile ff, String feedId);
    
    FlowFile releaseWaterMark(ProcessSession session, FlowFile ff, String feedId, String waterMarkName);
    
    FlowFile releaseAllWaterMarks(ProcessSession session, FlowFile ff, String feedId);

    
    Optional<InitializationStatus> getInitializationStatus(String feedId);
    
    InitializationStatus startFeedInitialization(String feedId);
    
    InitializationStatus completeFeedInitialization(String feedId);
    
    InitializationStatus failFeedInitialization(String feedId);
    
    
    void updateFeedStatus(ProcessSession session, FlowFile ff, String statusMsg);
    

    // TODO: Remove all following when working

    void recordFeedInitialization(String systemCategory, String feedName);

    boolean isFeedInitialized(String systemCategory, String feedName);

}
