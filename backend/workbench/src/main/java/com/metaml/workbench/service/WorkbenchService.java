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

    // same lookup without getTwinProcess's status recompute, which costs two engine queries and
    // writes back to the twin. Null if there's no such twin. For readers that only want the
    // links, AgentExecutionDelegate in particular, since it runs on an engine thread.
    TwinProcess findTwinProcess(String id);

    TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    // manual Bridge button: works out which visit of the activity the original is on
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId);

    // for callers that already know the visit. activityInstanceId is Camunda's own, which is
    // what tells repeat visits of a loop or multi-instance activity apart.
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId);

    // every open user task on the ORIGINAL instance, not the twin. returns a label per task
    // completed, empty list if there was nothing open.
    List<String> completeCurrentTasks(String twinProcessId);

    // AgentExecutionDelegate writes its variable itself, on the engine's own thread. This is only
    // so the twin's event log (and so the UI) shows it happened, like every other operation does.
    void recordAgentExecution(String twinProcessId, String variableName, Object agentName);
}
