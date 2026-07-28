package com.metaml.workbench.service;

import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;

import java.util.List;

public interface WorkbenchService {

    String sampleMethod();

    ProcessModel saveProcessModel(String id, String name, String bpmnXml);

    ProcessModel getProcessModel(String id);

    TwinProcess launchProcess(String modelId);

    TwinProcess getTwinProcess(String id);

    TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId);

    // activityInstanceId tells repeat visits (loops, multi-instance) apart - pass null for the
    // manual button, which only ever means "the current occurrence"
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId);

    // every open user task on the ORIGINAL instance, not the twin. returns a label per task
    // completed, empty list if there was nothing open.
    List<String> completeCurrentTasks(String twinProcessId);
}
