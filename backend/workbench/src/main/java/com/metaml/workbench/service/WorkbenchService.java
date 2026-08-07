package com.metaml.workbench.service;

import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;

import java.util.List;

public interface WorkbenchService {

    String sampleMethod();

    ProcessModel saveProcessModel(String id, String name, String bpmnXml);

    ProcessModel getProcessModel(String id);

    // New scope item 3 (BPMN Processing): one generated Java Delegate class per unique
    // delegateExpression on the saved model's service tasks. Read-only - this only generates
    // source, it doesn't write anything to disk yet (that's the Spring Boot generation step,
    // still blocked on the template project's exact shape).
    List<GeneratedDelegate> generateDelegates(String modelId);

    // Starts both instances. The twin runs a definition of its own, generated from the original
    // with the human taken out of every activity, so its token can actually be moved along with
    // the original's rather than sitting on a user task nobody will ever open.
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

    // Moves the twin's own token through its copy of the named activity, by correlating the
    // message its receive task is waiting on. Never call this from anywhere that runs inside an
    // engine command: a failure on the twin's side can't be allowed to take the human's
    // task-completion transaction down with it. AutoBridgeTrigger's after-commit worker is where
    // it belongs.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId);

    // Same, but for a parallel multi-instance activity, where more than one twin execution can be
    // waiting on the identical message at once - originalExecutionId is the execution the
    // original's own "start" event fired on, read for its local loopCounter to pick the one twin
    // sibling that corresponds to it. Null (or an activity with no loopCounter at all) behaves
    // exactly like the two-argument overload.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId, String originalExecutionId);

    // every open user task on the ORIGINAL instance, not the twin. returns a label per task
    // completed, empty list if there was nothing open.
    List<String> completeCurrentTasks(String twinProcessId);

    // AgentExecutionDelegate writes its variable itself, on the engine's own thread. This is only
    // so the twin's event log (and so the UI) shows it happened, like every other operation does.
    void recordAgentExecution(String twinProcessId, String variableName, Object agentName);
}
