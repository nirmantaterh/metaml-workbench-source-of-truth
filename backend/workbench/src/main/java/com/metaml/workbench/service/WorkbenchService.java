package com.metaml.workbench.service;

import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;

public interface WorkbenchService {

    String sampleMethod();

    ProcessModel saveProcessModel(String id, String name, String bpmnXml);

    ProcessModel getProcessModel(String id);

    TwinProcess launchProcess(String modelId);

    TwinProcess getTwinProcess(String id);

    TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId);
}
