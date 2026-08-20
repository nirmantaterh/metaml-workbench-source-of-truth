package com.metaml.workbench.service;

import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;

import java.util.List;

public interface WorkbenchService {

    String sampleMethod();

    // kept for the many existing callers that don't care about tenant ownership - resolves to
    // tenantId=null, same as any legacy model saved before tenancy existed
    default ProcessModel saveProcessModel(String id, String name, String bpmnXml) {
        return saveProcessModel(id, name, bpmnXml, null);
    }

    // Tenant ownership (see the Phase 0 governance audit): the model is the enterprise-owned
    // resource, established exactly once, here, at creation - never reassigned afterward, since
    // saveProcessModel never overwrites an existing id. tenantId is still just caller-supplied
    // metadata, not authentication; there is no login to derive it from yet.
    ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId);

    // First-class alternative to saveProcessModel for a process pair with its own independently
    // authored second BPMN (e.g. RedCollar's Manuf + Twin), rather than one derived automatically
    // from bpmnXml at generate time. Flows through exactly the same MODEL/GENERATE/LAUNCH workflow
    // stages, generatedProjects/modelIdByProjectId bookkeeping, and retention logic as
    // saveProcessModel - generateSpringBootProject branches on ProcessModel.hasAuthoredTwin() to
    // pick SpringBootProjectGenerator.generateWithAuthoredTwin() over the single-BPMN generate(),
    // but everything upstream and downstream of that one branch is shared, not duplicated.
    //
    // twinBpmnXml is unrelated to TwinProcess/TwinModelGenerator's own "twin" concept (a governance-
    // evolution shadow instance of a running process) - this is a second, independently modeled
    // process, deployed into the generated Target Platform as-is, never onto the Workbench's own
    // engine or its evolution machinery.
    ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId);

    ProcessModel getProcessModel(String id);

    // Authoring/catalog deletion: removes the model, its .bpmn artifact, and every generated
    // project it produced. Deliberately NOT a runtime teardown - twins, their Camunda process
    // instances, Camunda deployments, approvals and tenant/policy data all survive, because a twin
    // holds its model id as provenance only and Camunda deployments are shared between a model's
    // twins (deleting one cascade-deletes live instances).
    //
    // Throws IllegalStateException if any of the model's generated applications is running or
    // being launched - deleting a model never kills a running app as a side effect. Workflow
    // history is retained, which is also what permanently retires the model id: it can never be
    // recreated, or the new model would inherit the dead one's Generate/Launch history.
    boolean deleteProcessModel(String modelId);

    // New scope item 1 (Navigation & UI): "Edit Existing Project" needs something to list, not
    // just a lookup by an id the user already has to know. Newest first - that's the one someone
    // just saved and is the most likely thing they're about to come back and edit.
    List<ProcessModel> listProcessModels();

    // New scope item 3 (BPMN Processing): one generated Java Delegate class per unique
    // delegateExpression on the saved model's service tasks. Read-only - this only generates
    // source, it doesn't write anything to disk. Useful on its own for previewing what Generate
    // will produce before committing to a full project (see generateSpringBootProject below).
    List<GeneratedDelegate> generateDelegates(String modelId);

    // New scope item 4 (Spring Boot Generation): the second step of Model -> Generate -> Launch.
    // Copies Joanna's camundademo template, drops the saved model's real BPMN and its generated
    // delegates in, and writes a controller built around the model's actual process key. Each call
    // produces its own standalone project directory - nothing here launches it (that's still
    // pending; see SpringBootProjectGenerator's own header comment).
    GeneratedProject generateSpringBootProject(String modelId);

    // The last step of Model -> Generate -> Launch. Starts a project a prior call to
    // generateSpringBootProject already produced, as its own background process on an
    // auto-assigned port - multiple generated apps can run at once, each on its own port, per an
    // explicit product decision rather than an assumption.
    LaunchedProject launchGeneratedProject(String projectId);

    // The counterpart to launchGeneratedProject. Without it the only ways to get a generated app's
    // port back were relaunching the same project or killing the workbench, and nothing at all
    // could reclaim one that was launched by mistake. Returns whether there was actually something
    // running to stop - deliberately a boolean rather than an exception, since stopping an already
    // stopped project is a harmless no-op from the caller's point of view; the REST layer is where
    // that turns into a 404.
    boolean stopGeneratedProject(String projectId);

    // What's currently running, for the Evolve workflow's "connect to an existing deployed
    // application" step to read from.
    List<LaunchedProject> listRunningProjects();

    // Single source of truth for the Model -> Generate -> Launch breadcrumb - never throws for a
    // model with no recorded history, reads back as everything PENDING instead. See
    // WorkflowStateTracker's own header comment for why this is computed from an event log rather
    // than stored as separate mutable fields.
    WorkflowState getWorkflowState(String modelId);

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

    // Phase 4 (approval workflow): resolves a REQUIRE_APPROVAL decision. approveEvolution
    // resumes and actually runs the exact original operation, once, under the ORIGINAL pinned
    // policy decision - it does not ask PolicyDecisionEngine again. rejectApproval permanently
    // stops it; the governed action never runs. Both are tenant-scoped the same way every other
    // governance method is - tenantId must match the approval's own, or it's treated as not
    // found.
    AgentDecision approveEvolution(String approvalId, String tenantId);

    AgentDecision rejectApproval(String approvalId, String tenantId);

    // what a minimal approval UI would list for one tenant - PENDING and resolved alike, the
    // caller filters if it only wants PENDING
    List<Approval> listApprovals(String tenantId);

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
