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

    // activityInstanceId distinguishes repeat visits to the same activityId (a loop, a
    // multi-instance task) so each visit gets bridged on its own instead of only the first one.
    // pass null when there's no instance to distinguish, e.g. the manual "Bridge selected
    // activity" button, which only ever means "the current occurrence of this activity".
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId);

    // every open user task on the ORIGINAL instance, not the twin. returns a label per task
    // completed, empty list if there was nothing open.
    List<String> completeCurrentTasks(String twinProcessId);
}
