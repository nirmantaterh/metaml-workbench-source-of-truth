package com.metaml.workbench.service;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.WorkbenchStateStore;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkbenchServiceImpl implements WorkbenchService {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchServiceImpl.class);

    // auto-bridge has no caller to ask for a type, so it uses this one
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    // Still the live copy - everything reads and writes these. WorkbenchStateStore just mirrors
    // them to a file after each change so a restart doesn't leave the engine holding instances we
    // can no longer name.
    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    // twin+activity currently being evolved by somebody. forwardedBridgeActivities can't do this
    // job on its own: evolve is allowed to run again on an activity that's already in there (the
    // governance demo re-evolves one activity three times), so it only tells you an evolution
    // happened at some point, not that one is happening right now. entries are removed in a
    // finally, so this doesn't grow.
    private final Map<String, Boolean> evolutionsInFlight = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final WorkbenchStateStore stateStore;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, WorkbenchStateStore stateStore) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.stateStore = stateStore;
    }

    @PostConstruct
    void restoreState() {
        WorkbenchStateStore.Snapshot snapshot = stateStore.load();
        for (ProcessModel model : snapshot.models()) {
            processModels.put(model.getId(), model);
        }
        for (TwinProcess twin : snapshot.twins()) {
            twinProcesses.put(twin.getId(), twin);
        }
    }

    // after anything that changed a model or a twin. the event log counts as a change - it's the
    // artifact section 4 of the demo script walks through, losing it on restart is a real loss.
    private void persistState() {
        stateStore.save(processModels.values(), twinProcesses.values());
    }

    @Override
    public String sampleMethod() {
        return "this is a sample method";
    }

    @Override
    public ProcessModel saveProcessModel(String id, String name, String bpmnXml) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process model name must not be blank");
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("Process model bpmnXml must not be blank");
        }
        String modelId;
        if (id != null && !id.isBlank()) {
            // no overwriting - twins already launched still point at the old definition
            if (processModels.containsKey(id)) {
                throw new IllegalArgumentException("Process model already exists: " + id);
            }
            modelId = id;
        } else {
            modelId = UUID.randomUUID().toString();
        }

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
            // singleResult() throws if the XML has more than one executable process. their
            // mistake, not ours, so 400
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element: " + e.getMessage());
        }
        if (definition == null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN process must have isExecutable=\"true\" on the bpmn:process element");
        }

        ProcessModel model = new ProcessModel(modelId, name, bpmnXml, Instant.now(), definition.getId());
        // the containsKey above isn't enough on its own - two saves of the same id can both clear
        // it and both deploy, and the loser would silently replace the winner's definition
        ProcessModel existing = processModels.putIfAbsent(modelId, model);
        if (existing != null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException("Process model already exists: " + modelId);
        }
        persistState();
        logger.info("Saved process model {} and deployed process definition {}", modelId, definition.getId());
        return model;
    }

    // we deploy before we can check any of this, so a rejected model would otherwise leave its
    // deployment sitting in the engine and showing up in cockpit
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
        // ConcurrentHashMap.get(null) throws NPE, which came back as a 500 for a launch body
        // with no modelId at all
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        ProcessModel model = processModels.get(id);
        if (model == null) {
            throw new NoSuchElementException("Process model not found: " + id);
        }
        return model;
    }

    @Override
    public TwinProcess launchProcess(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        String twinId = UUID.randomUUID().toString();

        ProcessInstance original = runtimeService.startProcessInstanceById(
                model.getProcessDefinitionId(), "original-" + twinId);
        ProcessInstance twinInstance;
        try {
            twinInstance = runtimeService.startProcessInstanceById(
                    model.getProcessDefinitionId(), "twin-" + twinId);
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
        twin.setProcessDefinitionId(model.getProcessDefinitionId());
        twin.setOriginalProcessId(original.getProcessInstanceId());
        twin.setTwinProcessId(twinInstance.getProcessInstanceId());
        twin.setStatus("RUNNING");
        twin.setLaunchedAt(Instant.now());
        twin.getEventLog().add("Deployed process definition " + model.getProcessDefinitionId()
                + "; started original process instance " + original.getProcessInstanceId()
                + " and twin process instance " + twinInstance.getProcessInstanceId());

        twinProcesses.put(twin.getId(), twin);
        persistState();
        logger.info("Launched twin {} (original instance {}, twin instance {}) for model {}",
                twin.getId(), original.getProcessInstanceId(), twinInstance.getProcessInstanceId(), modelId);
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
        requireActivityInDefinition(twin.getProcessDefinitionId(), twinActivityId, "twinActivityId");

        // replace, don't append. lookups use findFirst() so a second link for the same
        // activity would just sit there doing nothing
        twin.getActivityLinks().removeIf(link -> link.getOriginalActivityId().equals(originalActivityId));
        twin.getActivityLinks().add(new ActivityLink(originalActivityId, twinActivityId));
        twin.getEventLog().add("Connected original activity " + originalActivityId
                + " to twin activity " + twinActivityId);
        persistState();
        logger.info("Connected activity {} to twin activity {} on twin process {}",
                originalActivityId, twinActivityId, twinProcessId);
        return twin;
    }

    @Override
    public AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType) {
        // check before logging anything. governance calls agentType.trim() and a null used to
        // 500 after the event log had already been written
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        // a missing activityId isn't an NPE, it's worse - it quietly returns "not connected"
        // and leaves a line about activity null in the log
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        TwinProcess twin = getTwinProcess(twinProcessId);
        // every path below writes to the event log, including the blocked ones, so snapshot at the
        // end rather than trying to hit each return
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

        if (!isActivityLinked(twin, activityId)) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " is not connected to a twin activity");
            logger.info("Evolve blocked for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not connected to twin process");
        }

        if (!hasReachedActivityInOriginal(twin, activityId)) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " has not actually been reached in the original process instance "
                    + twin.getOriginalProcessId());
            logger.info("Evolve blocked for activity {} on twin {}: activity not reached in real process instance",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not yet reached in the original process instance");
        }

        twin.getEventLog().add("Twin activity received evolution request");

        String claim = evolutionClaim(twinProcessId, activityId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Evolution skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Evolve skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity " + activityId + " is already being evolved");
        }
        try {
            AgentDecision decision = runEvolution(twin, twinProcessId, activityId, agentType);
            // mark handled so a later bridge doesn't stomp evolvedAgent_* with the default type.
            // evolve itself isn't gated on this, repeat calls still go through.
            if (decision.isApproved()) {
                twin.getForwardedBridgeActivities().add(activityId);
            }
            return decision;
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    private static String evolutionClaim(String twinProcessId, String activityId) {
        // twin ids are uuids and bpmn ids are NCNames, neither of which can contain a colon, so
        // no two different pairs flatten to the same key
        return twinProcessId + ":" + activityId;
    }

    // Same path as evolveActivity, just with the default agent type since the event can't tell
    // us one. Two callers: AutoBridgeTrigger and the manual endpoint, and the forwarded guard
    // makes whichever arrives second a no-op.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        return bridgeActivityEvent(twinProcessId, activityId, null);
    }

    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        // same deal as evolveActivity - the skip paths write to the log too
        try {
            return bridgeOnce(twin, twinProcessId, activityId, activityInstanceId);
        } finally {
            persistState();
        }
    }

    private AgentDecision bridgeOnce(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        if (!isActivityLinked(twin, activityId)) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is not connected to a twin activity");
            logger.info("Bridge skipped for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not connected to twin process");
        }

        if (!hasReachedActivityInOriginal(twin, activityId)) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " has not been reached in the original process instance "
                    + twin.getOriginalProcessId() + " yet");
            logger.info("Bridge skipped for activity {} on twin {}: not reached yet",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not yet reached in the original process instance");
        }

        // Claim before touching the forwarded set, and hold it until the evolution is done. The
        // add() below is atomic, but atomic on its own isn't the point - a manual evolve doesn't
        // go anywhere near that set until after it has already taken a quota slot, so without the
        // claim the bridge walks straight through the add() and takes a second slot for the same
        // activity.
        String claim = evolutionClaim(twinProcessId, activityId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Bridge skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already being forwarded to twin");
        }

        // a loop or multi-instance activity comes back round to the same activityId, and each
        // visit needs bridging on its own. Camunda gives the trigger a fresh activityInstanceId
        // per visit, so key on that where we have one. the manual button can't say which visit it
        // means, so it stays on activityId.
        String forwardedKey = (activityInstanceId == null || activityInstanceId.isBlank())
                ? activityId
                : activityInstanceId;

        try {
            if (!twin.getForwardedBridgeActivities().add(forwardedKey)) {
                twin.getEventLog().add("Bridge skipped: activity " + activityId
                        + " was already forwarded to twin");
                logger.info("Bridge skipped for activity {} on twin {}: already forwarded",
                        activityId, twinProcessId);
                return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                        "Activity event already forwarded to twin");
            }

            String twinActivityId = twin.resolveTwinActivityId(activityId);

            twin.getEventLog().add("Original activity " + activityId + " reached");
            twin.getEventLog().add("Forwarded event to twin activity " + twinActivityId);
            twin.getEventLog().add("Bridge using default agent type '" + DEFAULT_BRIDGE_AGENT_TYPE
                    + "' (no agent type supplied by the triggering event)");
            logger.info("Bridge forwarding activity {} to twin activity {} on twin {} with default agent type {}",
                    activityId, twinActivityId, twinProcessId, DEFAULT_BRIDGE_AGENT_TYPE);

            // un-mark on failure. otherwise a governance block or a node manager outage leaves the
            // activity stuck as "forwarded" forever and there's no way to retry it
            try {
                AgentDecision decision = runEvolution(twin, twinProcessId, activityId,
                        DEFAULT_BRIDGE_AGENT_TYPE);
                if (!decision.isApproved()) {
                    twin.getForwardedBridgeActivities().remove(forwardedKey);
                }
                return decision;
            } catch (RuntimeException e) {
                twin.getForwardedBridgeActivities().remove(forwardedKey);
                throw e;
            }
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    // Without this the original parks at its first user task forever, and evolve/bridge both
    // gate on hasReachedActivityInOriginal, so only that one activity would ever be usable.
    // Completes everything open rather than one named task - parallel gateway leaves several.
    // TODO: only the original moves. The twin instance is still sat at its own first task and
    // nobody reads its position yet, which is fine right up until it isn't.
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

        // Each complete() is its own engine transaction, so the list above is a snapshot that can
        // go stale mid-loop: a second request completing the same twin, or a branch that finishes
        // and takes its siblings with it. Letting task 3 blow up used to throw away the fact that
        // tasks 1 and 2 really did complete - the caller got a 500 and the event log said nothing.
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
                    // still there means the command rolled back and nothing happened. two requests
                    // racing on a shared parallel join is the way to reproduce it. one retry.
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
        // only surface an error if nothing at all moved, otherwise the partial progress is real
        // and the caller needs to know about it more than it needs the stack trace
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

    private boolean isActivityLinked(TwinProcess twin, String activityId) {
        return twin.getActivityLinks().stream()
                .anyMatch(link -> link.getOriginalActivityId().equals(activityId));
    }

    private boolean hasReachedActivityInOriginal(TwinProcess twin, String activityId) {
        return !historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .list()
                .isEmpty();
    }

    // shared tail of evolve and bridge, both have already checked linked + reached by here
    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId, String agentType) {
        // governance before the node manager, deliberately - it can deny a type the catalog is
        // perfectly happy with, and it owns the quota. Reserves, doesn't just check; the finally
        // at the bottom gives the slot back on every path that didn't actually evolve anything.
        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(twinProcessId, agentType);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Evolution blocked by governance: " + reservation.getReason());
            logger.info("Evolve blocked by governance for activity {} on twin {} with agent type {}: {}",
                    activityId, twinProcessId, agentType, reservation.getReason());
            return new AgentDecision(agentType, false, null, reservation.getReason());
        }

        boolean evolutionSucceeded = false;
        try {
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

            String twinActivityId = twin.resolveTwinActivityId(activityId);

            // this variable is the only real effect an evolution has. if it doesn't land
            // (usually the twin already ended) then nothing happened, so don't say approved.
            boolean variableSet = false;
            try {
                runtimeService.setVariable(twin.getTwinProcessId(), "evolvedAgent_" + twinActivityId,
                        availability.getAgentName());
                twin.getEventLog().add("Set process variable 'evolvedAgent_" + twinActivityId
                        + "' = " + availability.getAgentName() + " on twin process instance "
                        + twin.getTwinProcessId());
                variableSet = true;
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

            evolutionSucceeded = true;
            AgentDecision decision = new AgentDecision(agentType, true, availability.getAgentName(),
                    availability.getReason());
            twin.getEventLog().add("Node manager reports agent type " + agentType
                    + " available; selected agent " + availability.getAgentName());
            logger.info("Evolve approved for activity {} on twin {} with agent type {}",
                    activityId, twinProcessId, agentType);
            return decision;
        } finally {
            // fairly sure every early return and throw lands here, but if usage ever reads
            // wrong this pairing is the first thing I'd go look at
            if (!evolutionSucceeded) {
                governanceService.releaseEvolutionSlot(twinProcessId);
            }
        }
    }

}
