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

    // Completes every user task currently open on the twin's ORIGINAL process instance, so
    // execution moves on to the next activity and that activity becomes evolvable/bridgeable
    // (both gate on the activity having actually been reached). Returns a label per completed
    // task, or an empty list if none were open.
    List<String> completeCurrentTasks(String twinProcessId);
}
