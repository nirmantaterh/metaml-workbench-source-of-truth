package com.metaml.workbench.service;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.history.HistoricVariableUpdate;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.codegen.InvalidDelegateExpressionException;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.generation.DelegateWriteException;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.GeneratedProjectLaunchException;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.GovernanceRequest;
import com.metaml.workbench.governance.PolicyDecision;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.governance.PolicyEvaluationException;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.StageError;
import com.metaml.workbench.workflow.StageEvent;
import com.metaml.workbench.workflow.StageStatus;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.workflow.WorkflowStateTracker;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class WorkbenchServiceImpl implements WorkbenchService {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchServiceImpl.class);

    // auto-bridge has no caller to ask for a type, so it uses this one
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    // Tenant governance (Phase 3B): the one action name every EVOLVE_TWIN GovernanceRequest uses, so a tenant policy has one stable string to match against instead of every call site inventing its own
    private static final String EVOLVE_TWIN_ACTION = "EVOLVE_TWIN";

    // what a client-supplied model id is allowed to look like. Generated ids are UUIDs, which fit this comfortably; anything with a separator, a dot, or a drive letter in it does not.
    private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9_-]+");

    // still the live copy - WorkbenchStateStore just mirrors these to a file after each change
    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    // twin+visit being evolved right now - the evolvedAgent_* variable alone can't tell you that, since it isn't set until an evolution actually succeeds. Keyed per visit like everything else, or two visits of a multi-instance activity block each other for nothing.
    private final Map<String, Boolean> evolutionsInFlight = new ConcurrentHashMap<>();
    // Not backed by its own file - both maps below are rebuilt on every restart instead (see restoreGeneratedProjects()) from sources that already persist: the project directory itself (SpringBootProjectGenerator.scanExisting()) and the GENERATE stage's own detail in WorkflowStateTracker. A launched process is the one thing that genuinely does not survive a restart - that part of the picture (listRunningProjects/stopGeneratedProject) still comes straight from SpringBootProjectLauncher's own live registry, unchanged by this.
    private final Map<String, GeneratedProject> generatedProjects = new ConcurrentHashMap<>();
    // the only place a generated project's originating model is remembered - GeneratedProject itself carries no modelId (it's a workbench.generation concern, not a BPMN one), and both launch and stop need to know which model's breadcrumb a project's LAUNCH stage belongs to
    private final Map<String, String> modelIdByProjectId = new ConcurrentHashMap<>();
    // One lock object per model id, guarding the two authoring operations that can conflict over a model's existence: Generate and Delete. Never removed, for the same reason the launcher's own per-project locks aren't - bounded by the number of distinct model ids seen since startup, and in memory only. See modelLockFor() for why nothing else needs to take it.
    private final Map<String, Object> modelLocks = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final PolicyDecisionEngine policyDecisionEngine;
    private final ApprovalService approvalService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final TwinModelGenerator twinModelGenerator;
    private final WorkbenchStateStore stateStore;
    private final ProcessModelFileStore modelFileStore;
    private final ProcessModelArchiveStore processModelArchiveStore;
    private final DelegateClassGenerator delegateClassGenerator;
    private final SpringBootProjectGenerator springBootProjectGenerator;
    private final SpringBootProjectLauncher springBootProjectLauncher;
    // single source of truth for where a model's Model -> Generate -> Launch pipeline actually is - see the class's own header comment. Every method below that IS one of those three stages records into it; nothing else should.
    private final WorkflowStateTracker workflowStateTracker;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            PolicyDecisionEngine policyDecisionEngine, ApprovalService approvalService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, TwinModelGenerator twinModelGenerator,
            WorkbenchStateStore stateStore, ProcessModelFileStore modelFileStore,
            ProcessModelArchiveStore processModelArchiveStore,
            DelegateClassGenerator delegateClassGenerator, SpringBootProjectGenerator springBootProjectGenerator,
            SpringBootProjectLauncher springBootProjectLauncher, WorkflowStateTracker workflowStateTracker) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.policyDecisionEngine = policyDecisionEngine;
        this.approvalService = approvalService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.twinModelGenerator = twinModelGenerator;
        this.stateStore = stateStore;
        this.modelFileStore = modelFileStore;
        this.processModelArchiveStore = processModelArchiveStore;
        this.delegateClassGenerator = delegateClassGenerator;
        this.springBootProjectGenerator = springBootProjectGenerator;
        this.springBootProjectLauncher = springBootProjectLauncher;
        this.workflowStateTracker = workflowStateTracker;
    }

    @PostConstruct
    void restoreState() {
        WorkbenchStateStore.Snapshot snapshot = stateStore.load();
        // H2-backed archive is authoritative; the JSON snapshot is only consulted for a model that isn't in it at all - one saved by a build before the archive existed, never re-saved since. That's what keeps a genuinely legacy model restorable without giving the snapshot any say over a model the archive already knows about.
        List<ProcessModel> restoredModels = new ArrayList<>(processModelArchiveStore.findAll());
        for (ProcessModel legacyModel : snapshot.models()) {
            if (restoredModels.stream().noneMatch(m -> m.getId().equals(legacyModel.getId()))) {
                restoredModels.add(legacyModel);
            }
        }
        for (ProcessModel model : restoredModels) {
            processModels.put(model.getId(), model);
            // WorkflowStateTracker now genuinely persists (WorkflowEventStore) and has already loaded its own history by this point - Spring fully constructs a dependency bean, @PostConstruct included, before injecting it into a dependent one, so this reads the real post-restore state, not a stale empty tracker. Only backfill for a model that has NO persisted workflow history at all - one saved by a build before this class had real persistence. Backfilling a model that already has real history would wipe its genuine GENERATE/LAUNCH progress by overwriting MODEL with a fresh single event and leaving the rest of the fold looking at an otherwise-empty list.
            if (workflowStateTracker.hasNoHistory(model.getId())) {
                workflowStateTracker.record(model.getId(), WorkflowStage.MODEL, StageStatus.COMPLETED, null,
                        model.getCreatedAt());
            }
        }
        for (TwinProcess twin : snapshot.twins()) {
            twinProcesses.put(twin.getId(), twin);
        }
        restoreGeneratedProjects();
        reconcileApprovedApprovals();
    }

    // Generated-project persistence: generatedProjects/modelIdByProjectId are rebuilt here rather than loaded from their own file, because everything they hold is already derivable from two sources that already survive a restart: generatedProjects    <- SpringBootProjectGenerator.scanExisting() (the project directory itself is the source of truth for its own id/directory/processKey) modelIdByProjectId   <- each model's own GENERATE stage detail, already persisted via WorkflowStateTracker/WorkflowEventStore before this phase Order matters: this runs after processModels is populated above (needed to iterate models) and after workflowStateTracker's own restore() has already happened - guaranteed here, not just assumed, because Spring fully constructs a dependency bean (including its @PostConstruct) before injecting it into this one, the same reasoning reconcileApprovedApprovals() below already relies on for its own dependencies.
    private void restoreGeneratedProjects() {
        for (GeneratedProject project : springBootProjectGenerator.scanExisting()) {
            generatedProjects.put(project.projectId(), project);
        }
        if (generatedProjects.isEmpty()) {
            return;
        }
        for (ProcessModel model : processModels.values()) {
            WorkflowState state = workflowStateTracker.stateFor(model.getId());
            String projectId = currentProjectIdOf(state);
            // only wired up when the project this model's own GENERATE stage points at genuinely still exists on disk (just scanned above) - a stale detail left over from a project whose directory is gone since must not be allowed to silently claim whatever unrelated id happens to occupy that slot in generatedProjects
            if (projectId != null && generatedProjects.containsKey(projectId)) {
                modelIdByProjectId.put(projectId, model.getId());
            }
            // Retention, restart half: a superseded project's JVM does not normally survive a restart (nothing re-launches one, and the launcher's registry starts empty), so anything still on disk from an older generation is collectable now. This is also what collects a project that was superseded WHILE running in the previous session - the regenerate that superseded it correctly left it alone at the time. "Does not normally" is doing real work there: a HARD kill of the previous workbench (kill -9) skips @PreDestroy, so its generated apps are still running and still hold their ports, invisible to this instance's empty registry. Each launch recorded its port in this model's own LAUNCH history, so that is checked before collecting anything - see somethingIsListeningOn's comment for why this probe is restart-only and why it fails closed.
            if (aRecordedLaunchPortIsStillListening(state)) {
                logger.warn("Skipping generated-project cleanup for model {} on startup - a port it previously "
                        + "launched on is still listening, so a generated app from before this restart may still "
                        + "be running. Its projects will be collected once that port is free.", model.getId());
                continue;
            }
            cleanupSupersededProjects(model.getId(), state);
        }
    }

    // Every port this model was ever recorded as launching on, newest first, as written by launchGeneratedProject ("port N") and carried onto the STOPPED event by stopGeneratedProject. A STOPPED event is not treated as proof the port is free - the probe is what decides that - but a port that was never recorded cannot be checked at all, which is the known limit of this guard and why it is a best-effort safety net rather than a liveness mechanism.
    private boolean aRecordedLaunchPortIsStillListening(WorkflowState state) {
        for (StageEvent event : state.history()) {
            if (event.stage() != WorkflowStage.LAUNCH || event.detail() == null
                    || !event.detail().startsWith("port ")) {
                continue;
            }
            try {
                int port = Integer.parseInt(event.detail().substring("port ".length()).trim());
                if (springBootProjectLauncher.somethingIsListeningOn(port)) {
                    return true;
                }
            } catch (NumberFormatException notAPort) {
                // a LAUNCH detail that isn't "port N" is nothing to probe, not an error
            }
        }
        return false;
    }

    // Retention (latest generation only, chosen product policy): a model keeps exactly one generated project - its newest completed generation - and older ones are disposable once nothing is running out of them. Computes current project ID from completed GENERATE history.
    private static String currentProjectIdOf(WorkflowState state) {
        String current = null;
        for (StageEvent event : state.history()) {
            if (event.stage() == WorkflowStage.GENERATE && event.status() == StageStatus.COMPLETED
                    && event.detail() != null) {
                current = event.detail();
            }
        }
        return current;
    }

    // Lists superseded project IDs for a process model.
    private static List<String> supersededProjectIdsOf(WorkflowState state) {
        List<String> superseded = new ArrayList<>(allGeneratedProjectIdsOf(state));
        superseded.remove(currentProjectIdOf(state));
        return superseded;
    }

    // Lists all generated project IDs for a process model.
    private static List<String> allGeneratedProjectIdsOf(WorkflowState state) {
        List<String> projectIds = new ArrayList<>();
        for (StageEvent event : state.history()) {
            if (event.stage() != WorkflowStage.GENERATE || event.status() != StageStatus.COMPLETED) {
                continue;
            }
            String projectId = event.detail();
            if (projectId != null && !projectIds.contains(projectId)) {
                projectIds.add(projectId);
            }
        }
        return projectIds;
    }

    private void cleanupSupersededProjects(String modelId) {
        cleanupSupersededProjects(modelId, workflowStateTracker.stateFor(modelId));
    }

    // Best-effort cleanup of superseded projects.
    private void cleanupSupersededProjects(String modelId, WorkflowState state) {
        for (String projectId : supersededProjectIdsOf(state)) {
            try {
                deleteIfSuperseded(modelId, projectId);
            } catch (RuntimeException e) {
                logger.warn("Could not clean up superseded generated project {} for model {}: {}",
                        projectId, modelId, e.toString());
            }
        }
    }

    private void deleteIfSuperseded(String modelId, String projectId) {
        // never another model's project. supersededProjectIdsOf() read this id out of THIS model's own history, so a conflicting owner means two models' histories disagree about who generated it - unresolvable from here, and deleting on a guess is the one outcome that can't be undone
        String owner = modelIdByProjectId.get(projectId);
        if (owner != null && !owner.equals(modelId)) {
            logger.warn("Not deleting generated project {} while cleaning up model {} - it is recorded as "
                    + "belonging to model {}", projectId, modelId, owner);
            return;
        }
        boolean wasIdle = springBootProjectLauncher.runIfIdle(projectId, () -> {
            // Re-read the history INSIDE the idle lock rather than trusting the list this id came from. That list was computed before the lock was taken, and a concurrent regenerate can append a GENERATE event in between - so this is the check that makes "never delete the current project" true at the moment of deletion rather than a moment earlier. (A fresh UUID per generate means an id realistically can't come BACK to being current; this holds regardless of that, instead of depending on it.)
            if (projectId.equals(currentProjectIdOf(workflowStateTracker.stateFor(modelId)))) {
                logger.info("Generated project {} became the current generation for model {} before it could be "
                        + "cleaned up - retaining it", projectId, modelId);
                return;
            }
            if (springBootProjectGenerator.delete(projectId)) {
                // only after the directory is actually gone - a project still on disk must stay reachable through launchGeneratedProject, and scanExisting() would put it back on the next restart anyway
                generatedProjects.remove(projectId);
                modelIdByProjectId.remove(projectId, modelId);
            }
        });
        if (!wasIdle) {
            // the whole point of the policy's "superseded + running -> retain temporarily" arm
            logger.info("Retaining superseded generated project {} for model {} - it is still running or "
                    + "being launched; it will be collected when it next stops", projectId, modelId);
        }
    }

    // Phase 5 (crash reconciliation): an approval can be left in APPROVED if the JVM died between approveEvolution() marking it APPROVED and marking it COMPLETED/FAILED. This resolves every such approval on startup, never by guessing. evolvedAgentVariableIsSet is not new - it already exists (see alreadyEvolved's own comment) to answer the identical question for the manual-evolve/auto-bridge retry path: "did this exact evolution already land?" It reads Camunda's own committed variable history, which is durable and transactional with the setVariable call itself - not the workbench's own in-memory bookkeeping, and not something this phase invents. That is what makes this safe rather than a blind retry: the variable being absent is proof the operation never ran, and the variable being present is proof it did, both independent of whatever the Approval's own status says.
    private void reconcileApprovedApprovals() {
        List<Approval> approved = approvalService.listAllApproved();
        if (approved.isEmpty()) {
            return;
        }
        for (Approval approval : approved) {
            TwinProcess twin = twinProcesses.get(approval.twinId());
            if (twin == null) {
                approvalService.markFailed(approval.id(), "twin no longer exists after restart");
                logger.warn("Reconciled approval {} as FAILED: twin {} no longer exists", approval.id(),
                        approval.twinId());
                continue;
            }
            String evolvedAgentVariable = AgentVariables.evolvedAgent(approval.twinActivityId(),
                    approval.loopCounter());
            if (evolvedAgentVariableIsSet(twin, evolvedAgentVariable)) {
                // proven, not assumed: the side effect this approval represents already happened before the crash. Marking COMPLETED here does not repeat it.
                approvalService.markCompleted(approval.id(),
                        "reconciled on restart - '" + evolvedAgentVariable + "' was already set");
                twin.getEventLog().add("Approval " + approval.id()
                        + " reconciled as COMPLETED on restart (already executed before crash)");
                logger.info("Reconciled approval {} as COMPLETED: '{}' already set", approval.id(),
                        evolvedAgentVariable);
                continue;
            }
            // proven, not assumed: the variable was never set, so the operation genuinely never ran. Safe to run it now - this is its first real execution, not a retry of one that may have already happened.
            GovernanceDecision reservation = governanceService.reserveEvolutionSlot(approval.twinId(),
                    approval.agentType());
            if (!reservation.isAllowed()) {
                approvalService.markFailed(approval.id(), "reconciliation: " + reservation.getReason());
                logger.warn("Reconciled approval {} as FAILED: {}", approval.id(), reservation.getReason());
                continue;
            }
            boolean succeeded = false;
            try {
                twin.getEventLog().add("Approval " + approval.id()
                        + " reconciled on restart - never executed before the crash, running it now");
                AgentDecision decision = executeAfterGovernance(twin, approval.twinId(), approval.activityId(),
                        approval.twinActivityId(), approval.loopCounter(), approval.agentType());
                succeeded = decision.isApproved();
                if (succeeded) {
                    approvalService.markCompleted(approval.id(), decision.getAgentName());
                } else {
                    approvalService.markFailed(approval.id(), decision.getReason());
                }
                logger.info("Reconciled approval {} as {}", approval.id(), succeeded ? "COMPLETED" : "FAILED");
            } catch (RuntimeException e) {
                approvalService.markFailed(approval.id(), "reconciliation failed: " + e.getMessage());
                logger.warn("Reconciled approval {} as FAILED: {}", approval.id(), e.getMessage());
            } finally {
                if (!succeeded) {
                    governanceService.releaseEvolutionSlot(approval.twinId());
                }
            }
        }
        persistState();
    }

    // Persists process models and twin processes state.
    private void persistState() {
        stateStore.save(processModels.values(), twinProcesses.values());
    }

    @Override
    public String sampleMethod() {
        return "this is a sample method";
    }

    @Override
    public ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId) {
        return doSaveProcessModelEntry(id, name, bpmnXml, null, tenantId, null);
    }

    @Override
    public ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId, Long projectId) {
        return doSaveProcessModelEntry(id, name, bpmnXml, null, tenantId, projectId);
    }

    @Override
    public ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml,
            String twinBpmnXml, String tenantId) {
        if (twinBpmnXml == null || twinBpmnXml.isBlank()) {
            throw new IllegalArgumentException("Authored twin bpmnXml must not be blank");
        }
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, tenantId, null);
    }

    @Override
    public ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml,
            String twinBpmnXml, String tenantId, Long projectId) {
        if (twinBpmnXml == null || twinBpmnXml.isBlank()) {
            throw new IllegalArgumentException("Authored twin bpmnXml must not be blank");
        }
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, tenantId, projectId);
    }

    private ProcessModel doSaveProcessModelEntry(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process model name must not be blank");
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("Process model bpmnXml must not be blank");
        }
        String modelId;
        if (id != null && !id.isBlank()) {
            // Validates model ID syntax.
            if (!SAFE_MODEL_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Process model id may only contain letters, digits, "
                        + "'-' and '_': " + id);
            }
            // no overwriting - twins already launched still point at the old definition
            if (processModels.containsKey(id)) {
                throw new IllegalArgumentException("Process model already exists: " + id);
            }
            // Reject reuse of retired model IDs to preserve history.
            if (isRetiredModelId(id)) {
                throw new IllegalArgumentException("Process model id '" + id + "' has already been used and "
                        + "cannot be reused - its workflow history is kept after deletion");
            }
            modelId = id;
        } else {
            modelId = UUID.randomUUID().toString();
        }

        // Records IN_PROGRESS status before saving.
        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.IN_PROGRESS, null);
        try {
            return doSaveProcessModel(modelId, name, bpmnXml, twinBpmnXml, tenantId, projectId);
        } catch (RuntimeException e) {
            // Record FAILED status on save failure.
            workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.FAILED, e.getMessage(),
                    new StageError(e.getClass().getSimpleName(), "SAVE_MODEL", null, null, null, null, null));
            throw e;
        }
    }

    // twinBpmnXml is null for the ordinary single-BPMN path. When present, only bpmnXml (the primary/Manufacturing-side process) is deployed to the Workbench's own engine below - exactly as the single-BPMN path always has, so ProcessModel.processDefinitionId keeps meaning what it already means, and this model can still use every existing Twin-evolution/governance feature that reads that field. twinBpmnXml is validated structurally (one executable process, same rule bpmnXml itself must satisfy) but deliberately NOT deployed here: it plays no part in the Workbench's own TwinProcess/evolution machinery, only in the generated Target Platform, which gets its own separate Camunda engine at generation time (SpringBootProjectGenerator. generateWithAuthoredTwin).
    private ProcessModel doSaveProcessModel(String modelId, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        Deployment deployment;
        try {
            deployment = repositoryService.createDeployment()
                    .name(name)
                    .addInputStream(modelId + ".bpmn",
                            new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)))
                    .deploy();
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Invalid BPMN XML, could not deploy to process engine: "
                    + e.getMessage());
        }

        ProcessDefinition definition;
        try {
            definition = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .singleResult();
        } catch (ProcessEngineException e) {
            // singleResult() throws if the XML has more than one executable process. their mistake, not ours, so 400
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element: " + e.getMessage());
        }
        if (definition == null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN process must have isExecutable=\"true\" on the bpmn:process element");
        }
        if (twinBpmnXml != null) {
            try {
                requireExactlyOneExecutableProcess(twinBpmnXml);
            } catch (RuntimeException e) {
                discardDeployment(deployment.getId());
                throw e;
            }
        }

        ProcessModel model = new ProcessModel(modelId, name, bpmnXml, twinBpmnXml, Instant.now(),
                definition.getId(), tenantId);
        // the containsKey above isn't enough on its own - two saves of the same id can both clear it and both deploy, and the loser would silently replace the winner's definition
        ProcessModel existing = processModels.putIfAbsent(modelId, model);
        if (existing != null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException("Process model already exists: " + modelId);
        }
        Path bpmnFilePath;
        Path twinBpmnFilePath = null;
        try {
            // the Spring Boot generation step needs a real .bpmn file on disk, not just the copy of this XML that the archive/JSON snapshot already embed as a string field - that copy is a restart-recovery cache, not something meant to be opened directly
            bpmnFilePath = modelFileStore.save(modelId, bpmnXml);
            if (twinBpmnXml != null) {
                twinBpmnFilePath = modelFileStore.saveTwin(modelId, twinBpmnXml);
            }
        } catch (RuntimeException e) {
            // don't leave a model that's deployed and in memory but has no matching file - roll both back rather than leave a half-saved model the Generate step would silently fail against later
            processModels.remove(modelId, model);
            discardDeployment(deployment.getId());
            throw e;
        }
        // H2-backed archive is the model's real persistence now; the JSON snapshot below still covers twins and remains a redundant backup for models
        processModelArchiveStore.save(model, bpmnFilePath, twinBpmnFilePath, projectId);
        persistState();
        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        logger.info("Saved process model {} and deployed process definition {}", modelId, definition.getId());
        return model;
    }

    // Structural validation only - deliberately does NOT deploy to repositoryService. An authored twin BPMN (see doSaveProcessModel) never runs on the Workbench's own engine; it is only ever deployed inside the generated Target Platform, which gets its own separate engine at generation time. This mirrors the same "exactly one executable process" rule bpmnXml itself is held to via the real deployment above, without giving the twin XML a deployment/governance footprint on the Workbench engine it will never actually run on.
    private static void requireExactlyOneExecutableProcess(String bpmnXml) {
        BpmnModelInstance model;
        try {
            model = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid BPMN XML: " + e.getMessage());
        }
        long executableCount = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process.class)
                .stream()
                .filter(org.camunda.bpm.model.bpmn.instance.Process::isExecutable)
                .count();
        if (executableCount != 1) {
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element (found " + executableCount + ")");
        }
    }

    // we deploy before we can check any of this, so a rejected model would otherwise leave its deployment sitting in the engine and showing up in cockpit
    private void discardDeployment(String deploymentId) {
        try {
            repositoryService.deleteDeployment(deploymentId, true);
        } catch (ProcessEngineException e) {
            logger.warn("Could not remove deployment {} after rejecting the model: {}",
                    deploymentId, e.getMessage());
        }
    }

    @Override
    public ProcessModel getProcessModel(String id) {
        // ConcurrentHashMap.get(null) throws NPE - used to 500 on a launch body with no modelId
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        ProcessModel model = processModels.get(id);
        if (model == null) {
            throw new NoSuchElementException("Process model not found: " + id);
        }
        return model;
    }

    // A model id that a real model was once created under, but which no model currently holds. Keyed on MODEL/COMPLETED specifically, not on "has any history at all": a save that FAILED validation (bad BPMN, more than one process, not executable) records MODEL/IN_PROGRESS then MODEL/FAILED and never COMPLETED, and retrying that same id afterwards is normal, expected use - blocking it would turn every rejected save into a permanently burnt id.
    private boolean isRetiredModelId(String modelId) {
        for (StageEvent event : workflowStateTracker.stateFor(modelId).history()) {
            if (event.stage() == WorkflowStage.MODEL && event.status() == StageStatus.COMPLETED) {
                return true;
            }
        }
        return false;
    }

    // Model deletion is an AUTHORING operation, so it only needs to exclude the other authoring operation that can invent state for the same model - Generate. Everything else is already covered without a lock: Launch and cleanup both go through the launcher's own per-project lock (which deletion takes as well, via runIfAllIdle), and Evolve never touches a model at all - it operates on a twin, which by design outlives its model. Ordering discipline, since deletion holds both: model lock first, then project locks. Both paths that take both do it in that order, so they cannot deadlock against each other.
    private Object modelLockFor(String modelId) {
        return modelLocks.computeIfAbsent(modelId, id -> new Object());
    }

    // Authoring/catalog deletion, per the chosen product semantics: this removes what the model OWNS and nothing else. Twins, their Camunda process instances, Camunda deployments, approvals, tenant/policy data and workflow history all deliberately survive - a twin holds its model id as provenance only (nothing ever resolves it back to a ProcessModel), and Camunda deployments are shared between a model's twins, so deleting one would cascade-delete live process instances belonging to twins that are still running perfectly well. Refuses outright, rather than stopping anything, if any of this model's generated applications is running or mid-launch. Deleting a model is not a reason to kill a running application, and the caller is better placed than this method to decide whether to stop it.
    @Override
    public boolean deleteProcessModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        synchronized (modelLockFor(modelId)) {
            ProcessModel model = processModels.get(modelId);
            if (model == null) {
                throw new NoSuchElementException("Process model not found: " + modelId);
            }
            // every generation, not just the superseded ones - once the model is gone, its current generation has nothing left to belong to either
            List<String> projectIds = allGeneratedProjectIdsOf(workflowStateTracker.stateFor(modelId));
            boolean deleted = springBootProjectLauncher.runIfAllIdle(projectIds, () -> {
                for (String projectId : projectIds) {
                    // same ownership guard cleanupSupersededProjects uses - never delete a directory another model is recorded as owning
                    String owner = modelIdByProjectId.get(projectId);
                    if (owner != null && !owner.equals(modelId)) {
                        logger.warn("Not deleting generated project {} while deleting model {} - it is recorded "
                                + "as belonging to model {}", projectId, modelId, owner);
                        continue;
                    }
                    springBootProjectGenerator.delete(projectId);
                    generatedProjects.remove(projectId);
                    modelIdByProjectId.remove(projectId, modelId);
                }
                processModels.remove(modelId, model);
                modelFileStore.delete(modelId);
                processModelArchiveStore.deleteByModelId(modelId);
                persistState();
            });
            if (!deleted) {
                throw new IllegalStateException("Cannot delete process model " + modelId
                        + " - one of its generated applications is running or is being launched. Stop it first, "
                        + "then delete the model.");
            }
            // history is NOT touched: it is what retires this id for good (see isRetiredModelId)
            logger.info("Deleted process model {} and {} generated project(s); its workflow history, twins, "
                    + "Camunda state and approvals are retained", modelId, projectIds.size());
            return true;
        }
    }

    @Override
    public boolean canDeleteProcessModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        synchronized (modelLockFor(modelId)) {
            if (!processModels.containsKey(modelId)) {
                return false;
            }
            return springBootProjectLauncher.runIfAllIdle(
                    allGeneratedProjectIdsOf(workflowStateTracker.stateFor(modelId)), () -> { });
        }
    }

    @Override
    public List<ProcessModel> listProcessModels() {
        return processModels.values().stream()
                .sorted(Comparator.comparing(ProcessModel::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<ProcessModelSummaryDto> listProcessModelSummaries() {
        // Reads straight off the archive store rather than the in-memory processModels map above - the map has no notion of a project either. This omits the rare legacy model restored from the JSON snapshot in restoreState() (saved before the H2 archive existed, never re-saved since) - such a model was never assigned a project to begin with, so it has nothing to show in a picker organised by project anyway.
        return processModelArchiveStore.findAllSummaries();
    }

    @Override
    public List<GeneratedDelegate> generateDelegates(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        return delegateClassGenerator.generate(model.getBpmnXml());
    }

    @Override
    public GeneratedProject generateSpringBootProject(String modelId) {
        // model lock held across the whole generate, so a delete can't land between the model lookup below and the GENERATE record - which would otherwise leave a generated project and a COMPLETED event belonging to a model that no longer exists
        synchronized (modelLockFor(modelId)) {
            return doGenerateSpringBootProject(modelId);
        }
    }

    private GeneratedProject doGenerateSpringBootProject(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.IN_PROGRESS, null);
        try {
            GeneratedProject project;
            if (model.hasAuthoredTwin()) {
                // First-class generation mode for a model saved via saveProcessModelWithAuthoredTwin: both BPMNs are deployed into the generated Target Platform as-is (external-task workers, signal broadcaster, execution-listener stubs - see SpringBootProjectGenerator.generateWithAuthoredTwin's own header). Everything else below this branch - bookkeeping, workflow-stage recording, retention - is identical to the single-BPMN path; only which generator method runs differs.
                project = springBootProjectGenerator.generateWithAuthoredTwin(model.getBpmnXml(),
                        model.getAuthoredTwinBpmnXml(), model.getName());
            } else {
                // regenerated here rather than reusing generateDelegates' output - that method renders against DelegateClassGenerator's own default package, which is fine for previewing source but not where SpringBootProjectGenerator is about to place the file. Has to be SpringBootProjectGenerator.DELEGATE_PACKAGE specifically, or the class compiles but Spring's component scan never finds it (see that constant's own comment).
                List<GeneratedDelegate> delegates = delegateClassGenerator.generate(model.getBpmnXml(),
                        SpringBootProjectGenerator.DELEGATE_PACKAGE);
                project = springBootProjectGenerator.generate(model.getBpmnXml(), delegates, model.getName());
            }
            generatedProjects.put(project.projectId(), project);
            modelIdByProjectId.put(project.projectId(), modelId);
            // projectId as the detail, not just a bare COMPLETED - stopGeneratedProject/ launchGeneratedProject both key off project ids, and the breadcrumb needs a way to hand one to the caller without a second round trip through generatedProjects
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.COMPLETED, project.projectId());
            logger.info("Generated Target Harness Platform {} for model {}", project.projectId(), modelId);
            // Retention, primary trigger: this generation is now the current one, so every earlier generation of this model just became superseded. Deliberately after the COMPLETED record above, so "current" is read from committed history rather than from the local variable - a generate that failed before this point leaves the previous generation current, and correctly collects nothing.
            cleanupSupersededProjects(modelId);
            return project;
        } catch (RuntimeException e) {
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.FAILED, e.getMessage(),
                    generateErrorFrom(e));
            throw e;
        }
    }

    // Phase 3C: delegateExpression/bpmnElementId are only ever known when the failure is a DelegateWriteException - one specific generated delegate's file failing to write is the one point in the whole generate pipeline actually scoped to a single BPMN element. Everything else (a missing template directory, BPMN with no process element, ...) is a failure of the operation as a whole, not attributable to one delegate without guessing, so it stays null - same reasoning saveProcessModel's catch already uses for MODEL failures.
    private static StageError generateErrorFrom(RuntimeException e) {
        if (e instanceof DelegateWriteException dwe) {
            return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null,
                    "${" + dwe.beanName() + "}", dwe.bpmnElementId());
        }
        // the other failure that genuinely knows its BPMN element: one task declaring a delegateExpression that names no bean (see InvalidDelegateExpressionException). Carrying both fields is what makes the editor's "Go to error" able to select that exact task.
        if (e instanceof InvalidDelegateExpressionException bad) {
            return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null,
                    bad.rawExpression(), bad.bpmnElementId());
        }
        return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null, null, null);
    }

    @Override
    public LaunchedProject launchGeneratedProject(String projectId) {
        GeneratedProject project = generatedProjects.get(projectId);
        if (project == null) {
            // restart no longer loses this on its own (see restoreGeneratedProjects()) - a genuine miss here means the id was never real, or its project directory is gone/unreadable
            throw new NoSuchElementException("Generated project not found: " + projectId
                    + " - it may not exist, or its generated-project directory may be missing or unreadable");
        }
        // absent when this project's own model can no longer be identified - a legacy project whose workflow history predates persistence entirely, or one whose GENERATE detail didn't survive for some other reason. The launch still works, it just has no breadcrumb to update.
        String modelId = modelIdByProjectId.get(projectId);
        if (modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.IN_PROGRESS, null);
        }
        try {
            // Messaging is opt-in at the launcher level (see SpringBootProjectLauncher.launch), so it's enabled only when this generated project actually has a Twin. hasAuthoredTwin() alone isn't reliable here since an operationally-derived Twin (see OperationalTwinGenerator) never gets written back onto the ProcessModel.
            boolean generatedProjectHasMessaging = projectHasMessagingLayer(project.directory());
            Map<String, String> extraEnv = generatedProjectHasMessaging
                    ? Map.of("METAML_MESSAGING_ENABLED", "true")
                    : Map.of();
            LaunchedProject launched = springBootProjectLauncher.launch(project, extraEnv);
            if (modelId != null) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.COMPLETED,
                        "port " + launched.port());
            }
            ProcessModel model = modelId != null ? processModels.get(modelId) : null;
            String displayName = model != null ? model.getName() : project.displayName();
            return new LaunchedProject(launched.projectId(), launched.processKey(), launched.port(),
                    launched.launchedAt(), modelId, displayName != null ? displayName : launched.processKey());
        } catch (RuntimeException e) {
            if (modelId != null) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.FAILED, e.getMessage(),
                        launchErrorFrom(e, projectId));
            }
            throw e;
        }
    }

    // Whether generateWithAuthoredTwin wrote a RabbitMqConfig.java anywhere under this project.
    private static boolean projectHasMessagingLayer(Path projectDirectory) {
        if (!Files.isDirectory(projectDirectory)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(projectDirectory)) {
            return walk.anyMatch(path -> path.getFileName() != null
                    && "RabbitMqConfig.java".equals(path.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    // projectId is always known here (it's the method's own parameter); port/exitCode are only known when the launcher itself attached them (see GeneratedProjectLaunchException) - a launch that fails before a port was even chosen, if that ever happens, just leaves those two null rather than reporting a port that was never actually attempted
    private static StageError launchErrorFrom(RuntimeException e, String projectId) {
        if (e instanceof GeneratedProjectLaunchException launchFailure) {
            return new StageError(e.getClass().getSimpleName(), "LAUNCH_PROJECT", projectId, launchFailure.port(),
                    launchFailure.exitCode(), null, null);
        }
        return new StageError(e.getClass().getSimpleName(), "LAUNCH_PROJECT", projectId, null, null, null, null);
    }

    @Override
    public boolean stopGeneratedProject(String projectId) {
        // deliberately not gated on generatedProjects containing it: a launched PROCESS still never survives a restart (unlike the project directory itself, see restoreGeneratedProjects()), and refusing to stop what the launcher is still tracking would leave a running app nothing could reach. The launcher's own registry is the authority on what's running.
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        // read before stop(), not after - the launcher's registry no longer has an entry for projectId once it's actually stopped, and "which port was this running on" is exactly the kind of detail worth keeping on the STOPPED event rather than losing it the moment the fold's latest-event-wins rule overwrites the earlier COMPLETED event's own detail
        String portDetail = springBootProjectLauncher.find(projectId)
                .map(launched -> "port " + launched.port())
                .orElse(null);
        boolean wasRunning = springBootProjectLauncher.stop(projectId);
        String modelId = modelIdByProjectId.get(projectId);
        if (wasRunning && modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.STOPPED, portDetail);
        }
        // Retention, deferred trigger: this is the "superseded + running -> retain temporarily" arm coming due. A project that was superseded while running was left alone by the regenerate that superseded it; stopping is the existing lifecycle event that makes it collectable, so it's collected here instead of by anything polling for the moment it happens. Runs regardless of wasRunning - a project whose JVM already died externally reports false here (the launcher's own liveness self-heal got there first) and is exactly as collectable as one that stopped cleanly.
        if (modelId != null) {
            cleanupSupersededProjects(modelId);
        }
        return wasRunning;
    }

    @Override
    public WorkflowState getWorkflowState(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        return workflowStateTracker.stateFor(modelId);
    }

    @Override
    public List<LaunchedProject> listRunningProjects() {
        return springBootProjectLauncher.listRunning().stream()
                .map(launched -> {
                    String modelId = modelIdByProjectId.get(launched.projectId());
                    ProcessModel model = modelId != null ? processModels.get(modelId) : null;
                    GeneratedProject gp = generatedProjects.get(launched.projectId());
                    String displayName = model != null ? model.getName() : (gp != null ? gp.displayName() : launched.displayName());
                    return new LaunchedProject(launched.projectId(), launched.processKey(), launched.port(),
                            launched.launchedAt(), modelId, displayName != null ? displayName : launched.processKey());
                })
                .toList();
    }

    // One launch, one twin, and the twin always gets a definition of its own that its token can actually walk. There used to be a second entry point for that (launchProcessWithExecutableTwin) while the passive twin stayed the default; keeping both meant the UI had two buttons that produced twins behaving nothing alike, so this is the only one now.
    @Override
    public TwinProcess launchProcess(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        boolean twinWasAlreadyDeployed = repositoryService.createDeploymentQuery()
                .deploymentName(twinDeploymentName(model)).count() > 0;
        ProcessDefinition twinDefinition = deployTwinDefinition(model);
        try {
            return launch(model, twinDefinition.getId());
        } catch (RuntimeException e) {
            // Only clean up a deployment this call actually made. Duplicate filtering hands back the one an earlier launch created, and a twin from that launch can still be running on it - deleting it cascade-deletes a live instance.
            if (!twinWasAlreadyDeployed) {
                discardDeployment(twinDefinition.getDeploymentId());
            }
            throw e;
        }
    }

    private static String twinDeploymentName(ProcessModel model) {
        return model.getName() + " (twin " + model.getId() + ")";
    }

    // The twin is generated from what's actually deployed rather than from the stored XML, so it can't drift from the definition the original is running.
    private ProcessDefinition deployTwinDefinition(ProcessModel model) {
        BpmnModelInstance twinModel;
        try {
            twinModel = twinModelGenerator.generate(
                    repositoryService.getBpmnModelInstance(model.getProcessDefinitionId()));
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Could not read the deployed definition "
                    + model.getProcessDefinitionId() + " to build a twin from it: " + e.getMessage());
        }

        Deployment deployment;
        try {
            // Deployment name and resource name both have to be the same on every launch of this model or duplicate filtering has nothing to compare against, hence the model id in both rather than just the display name - two models are allowed to share a name. Without this, launching the same model ten times left ten twin deployments behind, each with its own process definition version, and nothing ever cleaned them up.
            deployment = repositoryService.createDeployment()
                    .name(twinDeploymentName(model))
                    .enableDuplicateFiltering(true)
                    .addModelInstance(model.getId() + "-twin.bpmn", twinModel)
                    .deploy();
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Generated twin BPMN did not deploy: " + e.getMessage());
        }

        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        if (definition == null) {
            discardDeployment(deployment.getId());
            throw new IllegalStateException("Generated twin BPMN deployed but produced no process definition");
        }
        logger.info("Deployed generated twin definition {} for model {}", definition.getId(), model.getId());
        return definition;
    }

    // twinDefinitionId is the original's own on the plain path, which is the whole difference between a twin that can move and one that can't
    private TwinProcess launch(ProcessModel model, String twinDefinitionId) {
        String twinId = UUID.randomUUID().toString();

        ProcessInstance original = runtimeService.startProcessInstanceById(
                model.getProcessDefinitionId(), BusinessKeys.originalKey(twinId));
        ProcessInstance twinInstance;
        try {
            twinInstance = runtimeService.startProcessInstanceById(twinDefinitionId, BusinessKeys.twinKey(twinId));
        } catch (RuntimeException e) {
            // kill the original as well, otherwise every failed launch leaks a live instance
            try {
                runtimeService.deleteProcessInstance(original.getProcessInstanceId(),
                        "Twin process instance failed to start; rolling back the original");
            } catch (ProcessEngineException cleanupFailure) {
                logger.warn("Could not roll back original process instance {} after twin start failed: {}",
                        original.getProcessInstanceId(), cleanupFailure.getMessage());
            }
            throw e;
        }

        TwinProcess twin = new TwinProcess();
        twin.setId(twinId);
        twin.setModelId(model.getId());
        // Tenant ownership (Phase 0 governance audit): a twin never picks its own tenant, it inherits whichever the model it was launched from already has - null for a model saved before tenancy existed, same as the model itself.
        twin.setTenantId(model.getTenantId());
        twin.setProcessDefinitionId(model.getProcessDefinitionId());
        twin.setTwinProcessDefinitionId(twinDefinitionId);
        twin.setOriginalProcessId(original.getProcessInstanceId());
        twin.setTwinProcessId(twinInstance.getProcessInstanceId());
        twin.setStatus("RUNNING");
        twin.setLaunchedAt(Instant.now());
        twin.getEventLog().add("Deployed process definition " + model.getProcessDefinitionId()
                + "; started original process instance " + original.getProcessInstanceId()
                + " and twin process instance " + twinInstance.getProcessInstanceId()
                + " on definition " + twinDefinitionId);

        twinProcesses.put(twin.getId(), twin);
        persistState();
        logger.info("Launched twin {} (original instance {}, twin instance {} on definition {}) for model {}",
                twin.getId(), original.getProcessInstanceId(), twinInstance.getProcessInstanceId(),
                twinDefinitionId, model.getId());
        return twin;
    }

    @Override
    public TwinProcess getTwinProcess(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Twin process id must not be blank");
        }
        TwinProcess twin = twinProcesses.get(id);
        if (twin == null) {
            throw new NoSuchElementException("Twin process not found: " + id);
        }
        // stored status goes stale as soon as either instance ends, so recompute every read
        twin.setStatus(computeStatus(twin));
        return twin;
    }

    @Override
    public List<TwinProcess> listTwinProcesses(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        return twinProcesses.values().stream()
                .filter(twin -> modelId.equals(twin.getModelId()))
                .peek(twin -> twin.setStatus(computeStatus(twin)))
                .toList();
    }

    @Override
    public TwinProcess findTwinProcess(String id) {
        return id == null ? null : twinProcesses.get(id);
    }

    private String computeStatus(TwinProcess twin) {
        boolean originalRunning = isInstanceRunning(twin.getOriginalProcessId());
        boolean twinRunning = isInstanceRunning(twin.getTwinProcessId());
        if (originalRunning && twinRunning) {
            return "RUNNING";
        }
        if (!originalRunning && !twinRunning) {
            return "ENDED";
        }
        return originalRunning ? "ORIGINAL_RUNNING_TWIN_ENDED" : "TWIN_RUNNING_ORIGINAL_ENDED";
    }

    private boolean isInstanceRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() != null;
    }

    private void requireActivityInDefinition(String processDefinitionId, String activityId, String fieldName) {
        BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
        if (modelInstance.getModelElementById(activityId) == null) {
            throw new IllegalArgumentException(
                    fieldName + " '" + activityId + "' does not exist in the deployed process definition");
        }
    }

    @Override
    public TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId) {
        if (twinProcessId == null || twinProcessId.isBlank()) {
            throw new IllegalArgumentException("twinProcessId must not be blank");
        }
        if (originalActivityId == null || originalActivityId.isBlank()) {
            throw new IllegalArgumentException("originalActivityId must not be blank");
        }
        if (twinActivityId == null || twinActivityId.isBlank()) {
            throw new IllegalArgumentException("twinActivityId must not be blank");
        }

        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null) {
            throw new NoSuchElementException("Twin process not found: " + twinProcessId);
        }

        requireActivityInDefinition(twin.getProcessDefinitionId(), originalActivityId, "originalActivityId");
        // the twin's own definition, not the original's. The two happen to share activity ids today, so checking the original passed for the wrong reason - and would keep passing for an id the generator had dropped, leaving a link pointing at nothing.
        requireActivityInDefinition(twin.getTwinProcessDefinitionId(), twinActivityId, "twinActivityId");

        // one twin activity, one original activity: evolvedAgent_<twinActivityId> and the twin's own advance message are both keyed on twinActivityId alone, so a second original activity sharing it would silently clobber whatever the first one wrote instead of getting its own slot. Rejected here rather than left to be discovered mid-run. Synchronized on the twin itself - caught by an adversarial review of this very fix: CopyOnWriteArrayList makes each individual list operation thread-safe, but not the check-then-remove-then-add sequence as a whole, so two concurrent calls connecting different originals to the same still-unclaimed twin activity could both pass the check before either one's add() was visible to the other. One lock per twin, not a global one, since only calls racing on the SAME twin can conflict.
        synchronized (twin) {
            twin.getActivityLinks().stream()
                    .filter(link -> link.getTwinActivityId().equals(twinActivityId))
                    .filter(link -> !link.getOriginalActivityId().equals(originalActivityId))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Twin activity '" + twinActivityId
                                + "' is already connected to original activity '" + existing.getOriginalActivityId()
                                + "'; connect original activity '" + originalActivityId
                                + "' to a different twin activity instead of sharing this one");
                    });

            // replace not append - lookups use findFirst() so a duplicate link would just sit unused
            twin.getActivityLinks().removeIf(link -> link.getOriginalActivityId().equals(originalActivityId));
            twin.getActivityLinks().add(new ActivityLink(originalActivityId, twinActivityId));
        }
        twin.getEventLog().add("Connected original activity " + originalActivityId
                + " to twin activity " + twinActivityId);
        persistState();
        logger.info("Connected activity {} to twin activity {} on twin process {}",
                originalActivityId, twinActivityId, twinProcessId);
        return twin;
    }

    @Override
    public AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType) {
        // check before logging - a null agentType used to 500 after the event log was already written
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        // missing activityId isn't an NPE, it quietly logs "activity null" and returns not-connected
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        TwinProcess twin = getTwinProcess(twinProcessId);
        // every path below writes to the event log, so persist once at the end instead of per-return
        try {
            return evolveOnce(twin, twinProcessId, activityId, agentType);
        } finally {
            persistState();
        }
    }

    private AgentDecision evolveOnce(TwinProcess twin, String twinProcessId, String activityId,
            String agentType) {
        twin.getEventLog().add("Original activity " + activityId
                + " requested evolution with agent type " + agentType);

        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " is not connected to a twin activity");
            // Phase 9/10 red team finding: this and the other operator-actionable skip reasons below used to log at the same INFO level as routine success, indistinguishable from the flood of ordinary per-activity lines - the concrete mechanism behind a twin silently stopping without any louder signal than the human side sailing through with ordinary 200 responses. Bumped to WARN with a stable, greppable prefix.
            logger.warn("TWIN_SKIPPED: evolve blocked for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not connected to twin process");
        }

        String visitId = currentVisitId(twin, activityId);
        if (visitId == null) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " has not actually been reached in the original process instance "
                    + twin.getOriginalProcessId());
            logger.info("Evolve blocked for activity {} on twin {}: activity not reached in real process instance",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not yet reached in the original process instance");
        }

        twin.getEventLog().add("Twin activity received evolution request");

        String claim = evolutionClaim(twinProcessId, visitId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Evolution skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Evolve skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity " + activityId + " is already being evolved");
        }
        try {
            // runEvolution sets evolvedAgent_<twinActivityId>[_loopCounter] on approval, which is exactly the signal bridgeOnce's alreadyEvolved() checks before letting the auto-bridge (or a repeat manual bridge) stomp this visit with the default agent type - no separate bookkeeping needed here for that to work.
            return runEvolution(twin, twinProcessId, activityId, twinActivityId,
                    loopCounterOf(twin, activityId, visitId), agentType);
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    private static String evolutionClaim(String twinProcessId, String activityInstanceId) {
        // twin ids are uuids, so the first colon here is always the separator
        return twinProcessId + ":" + activityInstanceId;
    }

    // Bridges activity event with default agent type.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        String visitId = currentVisitId(twin, activityId);
        // The button has to move the twin as well as evolve it, and not just for symmetry with the auto trigger: the original's very first activity starts inside startProcessInstanceById, before launchProcess has registered the twin, so the trigger never sees it. If nobody correlated that first message here the twin would sit on it forever and never reach the activities every later step is waiting to find. Safe from here - this runs on a request thread with no engine command around it, unlike the task listener. Only when the original really has been to this activity, though. A null visit means it hasn't, and advancing anyway put the twin a step ahead of the thing it is mirroring. Gating on decision.isApproved() instead would have been wrong: an evolution refused by governance or by the node manager says nothing about where the original's token is, and the twin should still follow it.
        return bridgeAndAdvance(twin, twinProcessId, activityId, visitId);
    }

    // Resolves original execution ID for a visit instance.
    private String originalExecutionIdForVisit(TwinProcess twin, String activityId, String activityInstanceId) {
        if (activityInstanceId == null) {
            return null;
        }
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            if (visit.getId().equals(activityInstanceId) && visit.getExecutionIds().length > 0) {
                return visit.getExecutionIds()[0];
            }
        }
        return null;
    }

    // Bridges activity event for a specific activity instance.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        return bridgeAndAdvance(twin, twinProcessId, activityId, activityInstanceId);
    }

    // Read-only: reconstructs what a bound ComponentExecutor has actually done for this activity,
    // straight from the same MetaML-owned process variables TwinAutomationDelegate itself wrote
    // (see AgentVariables) - never anything computed beyond that, never a mutation.
    @Override
    public TwinActivityExecutionState getActivityExecutionState(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return new TwinActivityExecutionState(activityId, null, null, "NOT_STARTED", null, Map.of());
        }

        // Single fetch of every MetaML-owned variable currently on the twin instance (or, once it
        // has ended, its history) - agentName/summary/output below all read from this one map
        // rather than three separate engine round-trips.
        Map<String, Object> variables = readTwinVariables(twin);

        Object agentNameValue = variables.get(AgentVariables.evolvedAgent(twinActivityId, null));
        String agentName = agentNameValue == null ? null : agentNameValue.toString();

        Object summaryValue = variables.get(AgentVariables.twinAutomation(twinActivityId, null));
        String summary = summaryValue == null ? null : summaryValue.toString();

        Map<String, Object> output = summary == null ? Map.of() : activityOutputsFrom(variables, twinActivityId);

        String status;
        if (summary != null) {
            status = "EXECUTED";
        } else if (activityFailedToExecute(twin, twinActivityId)) {
            status = "FAILED";
        } else if (agentName != null) {
            status = "BOUND";
        } else {
            status = "NOT_STARTED";
        }

        return new TwinActivityExecutionState(activityId, twinActivityId, agentName, status, summary, output);
    }

    // Same runtime-then-history fallback idiom as evolvedAgentVariableIsSet above, but returning
    // every variable rather than testing one - an ended twin instance has nothing left in
    // runtimeService, only in history.
    private Map<String, Object> readTwinVariables(TwinProcess twin) {
        Map<String, Object> runtime = null;
        try {
            runtime = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            // twin instance already ended - fall through to history below
        }
        if (runtime != null && !runtime.isEmpty()) {
            return runtime;
        }
        Map<String, Object> historic = new LinkedHashMap<>();
        for (HistoricVariableInstance instance : historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .list()) {
            historic.put(instance.getName(), instance.getValue());
        }
        return historic;
    }

    // Picks out exactly the twinAutomationOutput_<name>_<twinActivityId> variables for this one
    // activity and strips the AgentVariables encoding back down to the bare output name a
    // ComponentExecutor actually wrote (see AgentVariables#twinAutomationOutput) - deliberately
    // NOT a raw variable dump: every other variable on the twin instance is ignored.
    private Map<String, Object> activityOutputsFrom(Map<String, Object> variables, String twinActivityId) {
        String prefix = "twinAutomationOutput_";
        String suffix = "_" + twinActivityId;
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(prefix) && name.endsWith(suffix) && name.length() > prefix.length() + suffix.length()) {
                String outputName = name.substring(prefix.length(), name.length() - suffix.length());
                output.put(outputName, entry.getValue());
            }
        }
        return output;
    }

    // Best-effort FAILED signal reusing the twin's own event log rather than adding new tracking -
    // the exact prefix advanceTwinActivity's catch block below writes when correlate()/
    // messageEventReceived() throws for this activity.
    private boolean activityFailedToExecute(TwinProcess twin, String twinActivityId) {
        String marker = "Twin activity " + twinActivityId + " failed to execute:";
        return twin.getEventLog().stream().anyMatch(line -> line.startsWith(marker));
    }

    // Bridges and advances twin activity under concurrency control.
    private AgentDecision bridgeAndAdvance(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        if (activityInstanceId == null) {
            // nothing to advance either way - bridgeOnce's own "not reached yet" skip covers this
            AgentDecision decision;
            try {
                decision = bridgeOnce(twin, twinProcessId, activityId, null);
            } finally {
                persistState();
            }
            return decision;
        }

        String claim = evolutionClaim(twinProcessId, activityInstanceId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Bridge skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            persistState();
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already being forwarded to twin");
        }

        try {
            AgentDecision decision;
            try {
                decision = bridgeOnce(twin, twinProcessId, activityId, activityInstanceId);
            } finally {
                persistState();
            }
            try {
                // Caught by an adversarial review: without resolving this, a parallel multi-instance activity with more than one sibling still open would always hit the plain-correlate path below and throw MismatchingMessageCorrelationException, since a caller with no live ExecutionEvent has no execution id to read the way AutoBridgeTrigger does.
                advanceTwinActivity(twinProcessId, activityId,
                        originalExecutionIdForVisit(twin, activityId, activityInstanceId));
            } catch (RuntimeException e) {
                // the bridge itself worked and is already committed, so don't turn it into a failure - advanceTwinActivity has put the reason in the twin's event log already
                logger.warn("Bridged activity {} on twin {} but could not move the twin through it: {}",
                        activityId, twinProcessId, e.toString());
            }
            return decision;
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    // No claim of its own any more - bridgeAndAdvance above holds one claim across both this and the advance that follows it, so a second caller for the same visit never reaches this at all.
    private AgentDecision bridgeOnce(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is not connected to a twin activity");
            logger.warn("TWIN_SKIPPED: bridge skipped for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not connected to twin process");
        }

        if (activityInstanceId == null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " has not been reached in the original process instance "
                    + twin.getOriginalProcessId() + " yet");
            logger.info("Bridge skipped for activity {} on twin {}: not reached yet",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not yet reached in the original process instance");
        }

        Object loopCounter = loopCounterOf(twin, activityId, activityInstanceId);
        if (alreadyEvolved(twin, activityId, activityInstanceId, twinActivityId, loopCounter)) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " was already forwarded to twin");
            logger.info("Bridge skipped for activity {} on twin {}: already forwarded",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already forwarded to twin");
        }

        twin.getEventLog().add("Original activity " + activityId + " reached");
        twin.getEventLog().add("Forwarded event to twin activity " + twinActivityId);
        twin.getEventLog().add("Bridge using default agent type '" + DEFAULT_BRIDGE_AGENT_TYPE
                + "' (no agent type supplied by the triggering event)");
        logger.info("Bridge forwarding activity {} to twin activity {} on twin {} with default agent type {}",
                activityId, twinActivityId, twinProcessId, DEFAULT_BRIDGE_AGENT_TYPE);

        return runEvolution(twin, twinProcessId, activityId, twinActivityId, loopCounter,
                DEFAULT_BRIDGE_AGENT_TYPE);
    }

    // Phase 7 red team finding W4: "already forwarded" used to live only in forwardedBridgeActivities, an in-memory Set that a plain app restart wiped clean - silently reopening every already-bridged visit to a second evolution, since nothing else remembered it had already happened. First version of this fix checked evolvedAgent_<twinActivityId>[_loopCounter] on the twin's own runtime/history state instead, on the reasoning that runEvolution only ever sets it on approval - the same condition the old Set was only added under. An independent adversarial review found that reasoning incomplete: loopCounter only exists for a multi-instance visit, so it correctly makes the variable name visit-unique there (proven by the existing multi-instance bridge tests, untouched by this correction) - but a PLAIN activity revisited through an ordinary BPMN loop-back gateway has no loopCounter at all, and writes that exact same variable name on every visit. The first version saw visit 2 as "already forwarded" the instant visit 1 succeeded - precisely what the deleted Set's own comment had warned about ("one entry per visit, not per activity, or a loop's second time round looks like a duplicate"). Reproduced with a throwaway probe (a loop-back gateway re-entering a plain task) before writing this correction, not assumed; the regression test below records it. Fixed by branching on whether loopCounter is present. When it is, the original check still applies unchanged. When it isn't, this instead compares ORDINAL POSITION: which numbered visit (by start time) this activityInstanceId is on the original side, against how many times evolvedAgent_<twinActivityId> has actually been SET on the twin - both read straight from Camunda's own history, nothing new persisted either way. Ordering by start time is unambiguous for a loop-back specifically because it's a single token going around a cycle - the original cannot start visit 2 before visit 1 has ended. That would not hold for two genuinely concurrent tokens re-entering the same plain activity (for example an inclusive gateway split that loops back into it), which this does not attempt to disambiguate - a known, narrow residual gap, not silently assumed away. Deliberately counting SETS of the variable via HistoricDetail.variableUpdates(), not how many times the twin's automation task has finished - an earlier version of this correction used the automation-finished count and a second independent adversarial review broke it on the existing incident-retry regression test: a failed automation rolls its whole command back (see recordTwinAutomationIncident above), including the automation task's own historic instance, but NOT the evolve step's variable set, which is a separate, already-committed command that ran and succeeded before the automation was ever attempted. Counting automation completions treated a genuinely-already-evolved, automation-still-pending retry as "not yet evolved" and re-ran evolution a second time. Counting SETS of evolvedAgent_<twinActivityId> instead survives that: the first (evolve-succeeded, automation-failed) attempt already left exactly one, so a retry of the SAME visit correctly reads "already evolved," while a genuinely new loop-back visit correctly reads "not yet." Proven empirically with a throwaway probe (ZzHistoricDetailProbeTest, deleted after confirming) that setting one variable name twice on a process instance produces two distinct HistoricVariableUpdate rows, in order, not one collapsed to the latest value the way HistoricVariableInstance would.
    private boolean alreadyEvolved(TwinProcess twin, String originalActivityId, String activityInstanceId,
            String twinActivityId, Object loopCounter) {
        if (loopCounter != null) {
            return evolvedAgentVariableIsSet(twin, AgentVariables.evolvedAgent(twinActivityId, loopCounter));
        }
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(originalActivityId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();
        int visitOrdinal = -1;
        for (int i = 0; i < visits.size(); i++) {
            if (visits.get(i).getId().equals(activityInstanceId)) {
                visitOrdinal = i;
                break;
            }
        }
        if (visitOrdinal < 0) {
            // shouldn't happen - the caller already resolved this activityInstanceId from the same history - but treat "can't place this visit" as "not yet evolved" rather than guess
            return false;
        }
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, null);
        long evolutionCount = historyService.createHistoricDetailQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableUpdates()
                .list()
                .stream()
                .filter(HistoricVariableUpdate.class::isInstance)
                .map(HistoricVariableUpdate.class::cast)
                .filter(update -> evolvedAgentVariable.equals(update.getVariableName()))
                .count();
        return evolutionCount > visitOrdinal;
    }

    // durable across restarts by construction - a row in the engine's own tables, not app memory - the same "shared Camunda runtime is the source of truth" invariant everything here depends on. Falls back to history for a twin that has since ended, where runtimeService has nothing left to read.
    private boolean evolvedAgentVariableIsSet(TwinProcess twin, String evolvedAgentVariable) {
        try {
            if (runtimeService.getVariable(twin.getTwinProcessId(), evolvedAgentVariable) != null) {
                return true;
            }
        } catch (ProcessEngineException e) {
            // twin instance already ended - fall through to history below
        }
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName(evolvedAgentVariable)
                .count() > 0;
    }

    // The twin's copy of an activity is a receive task waiting on a message of its own, so the twin's token arrives and stops there. Correlating that message is what runs the project's automation, on its service task, and carries the token on to whatever comes next - which in turn stops at the message the next step of this will be looking for. Nothing here is an error worth shouting about. Gateways and end events have no message of their own, an activity nobody connected has no twin activity to look up, and an original that has walked past the point its twin is waiting at has nothing to correlate either.
    @Override
    public TwinAdvance advanceTwinActivity(String twinProcessId, String activityId) {
        return advanceTwinActivity(twinProcessId, activityId, null);
    }

    @Override
    public TwinAdvance advanceTwinActivity(String twinProcessId, String activityId, String originalExecutionId) {
        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null || activityId == null || activityId.isBlank()) {
            return TwinAdvance.skipped(null, "No such twin process: " + twinProcessId);
        }
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return TwinAdvance.skipped(null, "Activity " + activityId + " is not connected to a twin activity");
        }

        // Parallel multi-instance can have more than one twin sibling waiting on the identical message at once - plain and sequential activities never do, so this only ever resolves to something when there's genuinely more than one candidate to choose between.
        String messageName = TwinModelGenerator.twinMessageName(twinActivityId);
        String parallelSiblingExecutionId = originalExecutionId == null ? null
                : resolveParallelSibling(twin, messageName, originalExecutionId);

        // Asking first rather than letting correlate() throw MismatchingMessageCorrelationException: the twin sitting somewhere else is the ordinary case on nearly every trigger, and it isn't worth an exception and a stack trace each time.
        if (parallelSiblingExecutionId == null && !isTwinWaitingAt(twin, twinActivityId)) {
            return TwinAdvance.skipped(twinActivityId,
                    "Twin activity " + twinActivityId + " is not waiting to be advanced");
        }

        // Its own budget, deliberately not reserveEvolutionSlot's. An evolution is a request out to the node manager for an agent; this is the twin taking one step, which happens for every activity it passes. Sharing the counter would have ordinary automation eating the quota that exists to limit agent requests. Reserved after the waiting check rather than before it so the gateways and end events the trigger fires for, which have nothing to advance, don't spend anything.
        GovernanceDecision reservation = governanceService.reserveTwinExecutionSlot(twinProcessId);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Twin activity " + twinActivityId
                    + " left parked by governance: " + reservation.getReason());
            persistState();
            logger.warn("TWIN_SKIPPED: twin activity {} on twin {} left parked by governance: {}",
                    twinActivityId, twinProcessId, reservation.getReason());
            return TwinAdvance.skipped(twinActivityId, reservation.getReason());
        }

        try {
            if (parallelSiblingExecutionId != null) {
                // targets one named execution directly, bypassing correlate()'s ambiguity - the only way proven to release exactly one parallel sibling and leave the rest waiting, since correlate() throws the instant more than one execution matches
                runtimeService.messageEventReceived(messageName, parallelSiblingExecutionId);
            } else {
                // scoped to this instance, so a second twin on the same definition waiting at the same activity is not a candidate and correlate() never has to pick between them
                runtimeService.createMessageCorrelation(messageName)
                        .processInstanceId(twin.getTwinProcessId())
                        .correlate();
            }
        } catch (RuntimeException e) {
            governanceService.releaseTwinExecutionSlot(twinProcessId);
            twin.getEventLog().add("Twin activity " + twinActivityId + " failed to execute: " + e.getMessage());
            recordTwinAutomationIncident(twin, twinActivityId, parallelSiblingExecutionId, e);
            persistState();
            throw e;
        }

        twin.getEventLog().add("Twin activity " + twinActivityId + " executed on twin process instance "
                + twin.getTwinProcessId());
        persistState();
        logger.info("Twin activity {} executed on twin {} (message {})",
                twinActivityId, twinProcessId, messageName);
        return TwinAdvance.advanced(twinActivityId, messageName);
    }

    private static final String TWIN_AUTOMATION_INCIDENT_TYPE = "twinAutomationFailure";

    // A real, Cockpit-visible Incident - not a log line nobody's watching - and deliberately not tied to any job: the twin's automation Service Task is synchronous on purpose, so by the time this runs Camunda has already rolled the whole failed messageEventReceived()/ correlate() command back, and the twin's receive task sits exactly where it did before the failure - untouched, its event subscription intact, safe to retry later by simply re-bridging the same activity. Proven empirically (a throwaway probe that made an automation delegate throw, confirmed the subscription survived unchanged, then manually created an incident against that same execution, resolved it, and retried the identical correlation successfully) before writing this, not assumed. Deliberately NOT a retry loop. Automation is a per-project pluggable extension point (ProjectAutomationService), and only a specific implementation can know whether its own failures are safe to retry blindly - execute() carries no documented idempotency contract, and a blanket retry here could double-invoke something that charges a quota or calls an external agent with a real side effect. Surfacing a resolvable incident and leaving the twin exactly where it was is the smallest mechanism that fits without guessing at that contract; a project whose automation genuinely needs bounded retry for a known-transient dependency should implement that inside its own ProjectAutomationService, not here.
    private void recordTwinAutomationIncident(TwinProcess twin, String twinActivityId,
            String knownExecutionId, RuntimeException failure) {
        String executionId = knownExecutionId != null ? knownExecutionId
                : findWaitingExecutionId(twin, twinActivityId);
        if (executionId == null) {
            logger.warn("Twin activity {} on twin {} failed but no waiting execution was found to "
                    + "attach an incident to: {}", twinActivityId, twin.getId(), failure.toString());
            return;
        }
        try {
            runtimeService.createIncident(TWIN_AUTOMATION_INCIDENT_TYPE, executionId, twinActivityId,
                    failure.getMessage());
        } catch (RuntimeException incidentFailure) {
            // the original failure is still the one that matters and is already logged/rethrown by the caller - losing the incident record isn't worth masking it with a different one
            logger.warn("Could not record an incident for twin activity {} on twin {}: {}",
                    twinActivityId, twin.getId(), incidentFailure.toString());
            return;
        }
        logger.warn("Twin activity {} on twin {} failed to execute; incident recorded on execution {}: {}",
                twinActivityId, twin.getId(), executionId, failure.toString());
    }

    // Same "which execution is this activity id actually waiting on" question isTwinWaitingAt already answers as a boolean - this keeps the execution id instead of throwing it away, for attaching an incident to the right place when no more specific one is already known. Deliberately the ActivityInstance tree, the same way loopCounterOf/originalExecutionIdForVisit already resolve an execution for a specific activity, not createExecutionQuery() + getActiveActivityIds() the way isTwinWaitingAt checks. Found the hard way: that check can return a scope execution that merely sees the activity through a descendant (its own getActiveActivityIds() aggregates children), without the scope execution itself being positioned there - createIncident rejects exactly that with "activity is null", since it needs the actual leaf, not anything that can see it.
    private String findWaitingExecutionId(TwinProcess twin, String twinActivityId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getTwinProcessId());
        if (tree == null) {
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(twinActivityId)) {
            if (visit.getExecutionIds().length > 0) {
                return visit.getExecutionIds()[0];
            }
        }
        return null;
    }

    // Resolves which of possibly several twin siblings waiting on the same message corresponds to the original's own execution that just started - by loopCounter, since both sides create their multi-instance children in the same deterministic order for the same cardinality. Camunda resolves a non-local getVariable() up the scope chain, which is what actually finds loopCounter here: it's local to the per-iteration scope execution, one level above whichever execution holds the event subscription, and the plain (local-only) read misses it entirely - proven empirically before writing this, not assumed. Returns null when there's nothing to disambiguate (a plain or sequential activity, where at most one candidate is ever waiting), so isTwinWaitingAt's existing single-candidate path is untouched for those.
    private String resolveParallelSibling(TwinProcess twin, String messageName, String originalExecutionId) {
        Object originalLoopCounter = runtimeService.getVariable(originalExecutionId, "loopCounter");
        if (originalLoopCounter == null) {
            return null;
        }
        List<EventSubscription> subscriptions = runtimeService
                .createEventSubscriptionQuery()
                .processInstanceId(twin.getTwinProcessId())
                .eventName(messageName)
                .list();
        if (subscriptions.size() <= 1) {
            return null;
        }
        for (EventSubscription subscription : subscriptions) {
            if (originalLoopCounter.equals(
                    runtimeService.getVariable(subscription.getExecutionId(), "loopCounter"))) {
                return subscription.getExecutionId();
            }
        }
        logger.warn("Twin {} has {} candidates waiting on {} but none share loopCounter {} with "
                + "original execution {}; falling back to the single-candidate path",
                twin.getId(), subscriptions.size(), messageName, originalLoopCounter, originalExecutionId);
        return null;
    }

    // getActiveActivityIds rather than an activityId() execution query: inside a sequential multi-instance the token sits on a child execution, and the query would only match if we already knew which one to ask.
    private boolean isTwinWaitingAt(TwinProcess twin, String twinActivityId) {
        for (Execution execution : runtimeService.createExecutionQuery()
                .processInstanceId(twin.getTwinProcessId()).list()) {
            if (runtimeService.getActiveActivityIds(execution.getId()).contains(twinActivityId)) {
                return true;
            }
        }
        return false;
    }

    // without this the original parks at its first task forever and evolve/bridge never unblock completes everything open, not one named task - a parallel gateway leaves several
    @Override
    public List<String> completeCurrentTasks(String twinProcessId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        try {
            return completeOpenTasks(twin, twinProcessId);
        } finally {
            persistState();
        }
    }

    private List<String> completeOpenTasks(TwinProcess twin, String twinProcessId) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list();
        if (tasks.isEmpty()) {
            twin.getEventLog().add("No open user tasks to complete on original process instance "
                    + twin.getOriginalProcessId());
            logger.info("No open tasks to complete on original instance {} of twin {}",
                    twin.getOriginalProcessId(), twinProcessId);
            return List.of();
        }

        // each complete() is its own transaction, so this list can go stale mid-loop (a second request on the same twin, a branch finishing and taking its siblings with it) - task 3 blowing up used to throw away that tasks 1 and 2 really did complete
        List<String> completed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        RuntimeException firstRealFailure = null;
        for (Task task : tasks) {
            // definition key == the BPMN activity id, what connect/evolve key on
            String label = task.getName() == null
                    ? task.getTaskDefinitionKey()
                    : task.getName() + " (" + task.getTaskDefinitionKey() + ")";
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    taskService.complete(task.getId());
                    completed.add(label);
                    break;
                } catch (ProcessEngineException e) {
                    if (isTaskGone(task.getId())) {
                        // somebody else completed it, or its branch got cancelled out from under us
                        skipped.add(label);
                        break;
                    }
                    // still there means the command rolled back and nothing happened. two requests racing on a shared parallel join is the way to reproduce it. one retry.
                    if (attempt == 2 && firstRealFailure == null) {
                        firstRealFailure = e;
                        logger.warn("Could not complete task {} ({}) on original instance {}: {}",
                                task.getId(), label, twin.getOriginalProcessId(), e.getMessage());
                    }
                }
            }
        }

        if (!completed.isEmpty()) {
            twin.getEventLog().add("Completed " + completed.size()
                    + " open user task(s) on original process instance " + twin.getOriginalProcessId()
                    + ": " + String.join(", ", completed));
        }
        if (!skipped.isEmpty()) {
            twin.getEventLog().add("Skipped " + skipped.size()
                    + " user task(s) that were already gone by the time we got to them: "
                    + String.join(", ", skipped));
            logger.info("Skipped {} already-gone task(s) on original instance {} of twin {}: {}",
                    skipped.size(), twin.getOriginalProcessId(), twinProcessId, skipped);
        }
        // only surface an error if nothing at all moved, otherwise the partial progress is real and the caller needs to know about it more than it needs the stack trace
        if (completed.isEmpty() && firstRealFailure != null) {
            throw firstRealFailure;
        }
        if (firstRealFailure != null) {
            twin.getEventLog().add("At least one task could not be completed: "
                    + firstRealFailure.getMessage());
        }

        logger.info("Completed {} open task(s) on original instance {} of twin {}: {}",
                completed.size(), twin.getOriginalProcessId(), twinProcessId, completed);
        return completed;
    }

    private boolean isTaskGone(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult() == null;
    }

    @Override
    public void recordAgentExecution(String twinProcessId, String variableName, Object agentName) {
        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null) {
            return;
        }
        twin.getEventLog().add("Set process variable '" + variableName + "' = " + agentName
                + " on original process instance " + twin.getOriginalProcessId());
        persistState();
    }

    // Everything that guards against doing an activity twice keys on Camunda's activity instance id, because a loop or multi-instance activity comes round again under the same activity id. Callers that only know the activity id (the manual Bridge button, manual Evolve) have to resolve the same id here or their guard is looking at a different namespace than the auto-bridge's. Null means the original never got there.
    private String currentVisitId(TwinProcess twin, String activityId) {
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .orderByHistoricActivityInstanceStartTime().desc()
                .list();
        // one it's sitting on right now is what the button means. if it already walked past, the newest finished visit is the closest thing to what the caller is pointing at.
        for (HistoricActivityInstance visit : visits) {
            if (visit.getEndTime() == null) {
                return visit.getId();
            }
        }
        return visits.isEmpty() ? null : visits.get(0).getId();
    }

    // AgentExecutionDelegate reads loopCounter straight off the execution it's completing; over here all we have is the visit, so go the long way round to the same value. Null for a plain activity, which is what keeps its variable name short.
    private Object loopCounterOf(TwinProcess twin, String activityId, String activityInstanceId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            // original already ended, so nothing is holding a loop counter any more
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            if (visit.getId().equals(activityInstanceId) && visit.getExecutionIds().length > 0) {
                return runtimeService.getVariableLocal(visit.getExecutionIds()[0], "loopCounter");
            }
        }
        return null;
    }

    // shared tail of evolve and bridge, both have already checked linked + reached by here
    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        // governance before the node manager on purpose - it can deny a type the catalog is fine with
        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(twinProcessId, agentType);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Evolution blocked by governance: " + reservation.getReason());
            logger.info("Evolve blocked by governance for activity {} on twin {} with agent type {}: {}",
                    activityId, twinProcessId, agentType, reservation.getReason());
            return new AgentDecision(agentType, false, null, reservation.getReason());
        }

        boolean evolutionSucceeded = false;
        try {
            // Tenant governance (Phase 3B/4): after the existing platform check above has already allowed this, ask the tenant's own policy before doing anything the node manager or the twin's own process instance would need rolling back. A twin nobody has assigned to a tenant yet (tenantId null - every twin launched before tenant ownership existed) has no policy to ask, so it's ungoverned here exactly like it was before this phase - not silently given a made-up tenant, not silently denied.
            if (twin.getTenantId() != null) {
                AgentDecision tenantDecision = enforceTenantPolicy(twin, activityId, twinProcessId, twinActivityId,
                        loopCounter, agentType);
                if (tenantDecision != null) {
                    return tenantDecision;
                }
            }

            AgentDecision decision = executeAfterGovernance(twin, twinProcessId, activityId, twinActivityId,
                    loopCounter, agentType);
            evolutionSucceeded = decision.isApproved();
            return decision;
        } finally {
            // fairly sure every early return and throw lands here, but if usage ever reads wrong this pairing is the first thing I'd go look at
            if (!evolutionSucceeded) {
                governanceService.releaseEvolutionSlot(twinProcessId);
            }
        }
    }

    // Tenant governance (Phase 3B/4): asks the PolicyDecisionEngine whether this tenant's own policy allows this evolution. Returns null to mean "proceed exactly as before" (ALLOW, or evaluation genuinely couldn't run - see the catch below); returns a real AgentDecision to mean "stop here, this is the actual result." Called from inside runEvolution's own try/finally, before the evolution slot has been marked succeeded, so a DENY or REQUIRE_APPROVAL here still releases the slot the platform check already reserved.
    private AgentDecision enforceTenantPolicy(TwinProcess twin, String activityId, String twinProcessId,
            String twinActivityId, Object loopCounter, String agentType) {
        GovernanceRequest request = new GovernanceRequest(twin.getTenantId(), EVOLVE_TWIN_ACTION,
                Map.of("twinId", twinProcessId, "activityId", activityId, "agentType", agentType));

        PolicyDecision policyDecision;
        try {
            policyDecision = policyDecisionEngine.evaluate(request);
        } catch (NoSuchElementException | PolicyEvaluationException e) {
            // the tenant record is gone, or a stored rule is malformed - a real evaluation failure, not "no policy". Fails closed for the same reason the engine itself never turns a failure into a silent ALLOW: a broken policy must not be able to let something through that no one actually approved.
            twin.getEventLog().add("Evolution blocked: tenant policy could not be evaluated: " + e.getMessage());
            logger.warn("Tenant policy evaluation failed for activity {} on twin {} (tenant {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), e.getMessage());
            return new AgentDecision(agentType, false, null,
                    "Tenant policy could not be evaluated: " + e.getMessage(), false, PolicyEffect.DENY.name());
        }

        if (policyDecision.decision() == PolicyEffect.DENY) {
            twin.getEventLog().add("Evolution denied by tenant policy: " + policyDecision.reason());
            logger.info("Evolve denied by tenant policy for activity {} on twin {} (tenant {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), policyDecision.reason());
            return new AgentDecision(agentType, false, null, "Denied by tenant policy: " + policyDecision.reason(),
                    false, PolicyEffect.DENY.name());
        }
        if (policyDecision.decision() == PolicyEffect.REQUIRE_APPROVAL) {
            // Phase 4: persist exactly what would have run, pinned to THIS policy decision (policyId/policyVersionId/matchedRuleId/reason) - resolving it later never asks PolicyDecisionEngine again, so a tenant activating a new version afterward cannot retroactively change what this approval means. loopCounter is an Integer here (Camunda's own multi-instance loop counters are ints, never anything else) so it survives a JSON round trip; Object above is the engine API's own type, not a persistence concern.
            Integer loopCounterValue = loopCounter instanceof Integer i ? i : null;
            Approval approval = approvalService.create(twin.getTenantId(), twinProcessId, activityId,
                    twinActivityId, loopCounterValue, agentType, EVOLVE_TWIN_ACTION, policyDecision.policyId(),
                    policyDecision.policyVersionId(), policyDecision.policyVersionNumber(),
                    policyDecision.matchedRuleId(), policyDecision.reason());
            twin.getEventLog().add("Evolution requires approval per tenant policy (approval " + approval.id()
                    + "): " + policyDecision.reason());
            logger.info("Evolve requires approval for activity {} on twin {} (tenant {}, approval {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), approval.id(), policyDecision.reason());
            return new AgentDecision(agentType, false, null,
                    "Approval required (id " + approval.id() + "): " + policyDecision.reason(), false,
                    PolicyEffect.REQUIRE_APPROVAL.name());
        }
        // ALLOW - fall through, existing behavior continues unchanged
        return null;
    }

    // Phase 4: the actual work an evolution does, once governance (platform and tenant) has already said yes. Extracted out of runEvolution so the approval-resume path below can run the exact same code without going through enforceTenantPolicy a second time - the whole point of pinning a policy decision on the Approval is that resolving it does not re-evaluate PolicyDecisionEngine under whatever the tenant's policy says NOW.
    private AgentDecision executeAfterGovernance(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, loopCounter);
        twin.getEventLog().add("Contacting node manager for agent type " + agentType);

        AgentAvailabilityResult availability;
        try {
            availability = nodeManagerClient.checkAgentAvailability(agentType);
        } catch (NodeManagerUnavailableException e) {
            twin.getEventLog().add("Node manager unavailable: " + e.getMessage());
            logger.warn("Node manager unavailable while evolving activity {} on twin {}: {}",
                    activityId, twinProcessId, e.getMessage());
            throw e;
        }

        if (!availability.isAvailable()) {
            twin.getEventLog().add("Node manager reports agent type " + agentType
                    + " unavailable: " + availability.getReason());
            logger.info("Evolve blocked for activity {} on twin {} with agent type {}",
                    activityId, twinProcessId, agentType);
            return new AgentDecision(agentType, false, null, availability.getReason());
        }

        // this variable is the only real effect an evolution has. if it doesn't land (usually the twin already ended) then nothing happened, so don't say approved.
        boolean variableSet = false;
        try {
            runtimeService.setVariable(twin.getTwinProcessId(), evolvedAgentVariable,
                    availability.getAgentName());
            twin.getEventLog().add("Set process variable '" + evolvedAgentVariable
                    + "' = " + availability.getAgentName() + " on twin process instance "
                    + twin.getTwinProcessId());
            variableSet = true;

            writeAgentOutputs(twin, twinActivityId, loopCounter, availability.getOutputs());
        } catch (ProcessEngineException e) {
            twin.getEventLog().add("Could not set process variable on twin instance "
                    + twin.getTwinProcessId() + " (it may have already ended): " + e.getMessage());
            logger.warn("Could not set process variable on twin instance {}: {}",
                    twin.getTwinProcessId(), e.getMessage());
        }

        if (!variableSet) {
            logger.info("Evolve not approved for activity {} on twin {}: twin instance {} could not be updated",
                    activityId, twinProcessId, twin.getTwinProcessId());
            return new AgentDecision(agentType, false, null,
                    "Twin process instance " + twin.getTwinProcessId()
                            + " could not be updated (it may have already ended), so no agent was assigned");
        }

        AgentDecision decision = new AgentDecision(agentType, true, availability.getAgentName(),
                availability.getReason(), availability.isRiskFlagged(), null);
        twin.getEventLog().add("Node manager reports agent type " + agentType
                + " available; selected agent " + availability.getAgentName());
        logger.info("Evolve approved for activity {} on twin {} with agent type {}",
                activityId, twinProcessId, agentType);
        return decision;
    }

    // Phase 4: PENDING -> REJECTED. The governed action must never run - ApprovalService's own PENDING-only guard is what actually prevents that, this just records why on the twin too.
    @Override
    public AgentDecision rejectApproval(String approvalId, String tenantId) {
        Approval approval = approvalService.markRejected(approvalId, tenantId);
        TwinProcess twin = twinProcesses.get(approval.twinId());
        if (twin != null) {
            twin.getEventLog().add("Approval " + approvalId + " rejected: " + approval.reason());
            persistState();
        }
        logger.info("Approval {} rejected for tenant {}", approvalId, tenantId);
        return new AgentDecision(approval.agentType(), false, null, "Rejected: " + approval.reason(), false,
                ApprovalStatus.REJECTED.name());
    }

    // Phase 4: PENDING -> APPROVED -> COMPLETED|FAILED. markApproved is the one atomic gate - a second approve() call on the same id (Step 6/7's double-approval case) finds it already APPROVED and throws before this method ever touches the twin or the node manager, so the real side effect can only be attempted once per approval. The platform quota is reserved again here, freshly, for this actual attempt - the original request's reservation was already released when REQUIRE_APPROVAL first paused it (see runEvolution's finally).
    @Override
    public AgentDecision approveEvolution(String approvalId, String tenantId) {
        Approval approval = approvalService.markApproved(approvalId, tenantId);
        TwinProcess twin = twinProcesses.get(approval.twinId());
        if (twin == null) {
            approvalService.markFailed(approvalId, "twin " + approval.twinId() + " no longer exists");
            return new AgentDecision(approval.agentType(), false, null,
                    "Twin " + approval.twinId() + " no longer exists", false, ApprovalStatus.FAILED.name());
        }

        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(approval.twinId(),
                approval.agentType());
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Approval " + approvalId + " could not execute: " + reservation.getReason());
            approvalService.markFailed(approvalId, reservation.getReason());
            persistState();
            return new AgentDecision(approval.agentType(), false, null, reservation.getReason(), false,
                    ApprovalStatus.FAILED.name());
        }
        boolean succeeded = false;
        try {
            twin.getEventLog().add("Approval " + approvalId + " approved, resuming the original evolution");
            AgentDecision decision = executeAfterGovernance(twin, approval.twinId(), approval.activityId(),
                    approval.twinActivityId(), approval.loopCounter(), approval.agentType());
            succeeded = decision.isApproved();
            if (succeeded) {
                approvalService.markCompleted(approvalId, decision.getAgentName());
            } else {
                approvalService.markFailed(approvalId, decision.getReason());
            }
            persistState();
            logger.info("Approval {} resolved for tenant {}: {}", approvalId, tenantId,
                    succeeded ? "COMPLETED" : "FAILED");
            return decision;
        } finally {
            if (!succeeded) {
                governanceService.releaseEvolutionSlot(approval.twinId());
            }
        }
    }

    @Override
    public List<Approval> listApprovals(String tenantId) {
        return approvalService.listForTenant(tenantId);
    }

    // Has to be reconciled both ways, not just written. Re-evolving Task_Credit with an ordinary agent after a credit-risk-assessor run left the old risk flag sitting there otherwise, and the process kept escalating even though the twin now showed a plain agent with nothing wrong. Which outputs the last evolution left behind isn't something you can work out from the current ones, hence the index variable alongside them.
    private void writeAgentOutputs(TwinProcess twin, String twinActivityId, Object loopCounter,
            Map<String, Object> outputs) {
        Map<String, Object> current = outputs == null ? Map.of() : outputs;
        String indexVariable = AgentVariables.evolvedAgentOutputIndex(twinActivityId, loopCounter);

        for (String previousName : AgentVariables.outputNamesIn(
                runtimeService.getVariable(twin.getTwinProcessId(), indexVariable))) {
            if (!current.containsKey(previousName)) {
                runtimeService.removeVariable(twin.getTwinProcessId(),
                        AgentVariables.evolvedAgentOutput(previousName, twinActivityId, loopCounter));
            }
        }

        for (Map.Entry<String, Object> output : current.entrySet()) {
            String outputVariable = AgentVariables.evolvedAgentOutput(output.getKey(), twinActivityId,
                    loopCounter);
            runtimeService.setVariable(twin.getTwinProcessId(), outputVariable, output.getValue());
            twin.getEventLog().add("Set process variable '" + outputVariable + "' = " + output.getValue()
                    + " on twin process instance " + twin.getTwinProcessId());
        }

        // absence means "this evolution reported nothing", same convention the outputs themselves use
        if (current.isEmpty()) {
            runtimeService.removeVariable(twin.getTwinProcessId(), indexVariable);
        } else {
            runtimeService.setVariable(twin.getTwinProcessId(), indexVariable,
                    AgentVariables.outputIndexValue(current.keySet()));
        }
    }

    @Override
    public List<AgentAvailabilityResult> listAvailableAgents() {
        return nodeManagerClient.listAgents();
    }
}
