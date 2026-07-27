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

    // the auto-bridge has nobody to ask for an agent type, so it uses this one
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
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
            // don't overwrite: twins already launched still point at the old processDefinitionId
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
            // singleResult() throws if the XML declares more than one executable process.
            // Caller's mistake, so 400 not 500.
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element: " + e.getMessage());
        }
        if (definition == null) {
            throw new IllegalArgumentException(
                    "BPMN process must have isExecutable=\"true\" on the bpmn:process element");
        }

        ProcessModel model = new ProcessModel(modelId, name, bpmnXml, Instant.now(), definition.getId());
        processModels.put(modelId, model);
        logger.info("Saved process model {} and deployed process definition {}", modelId, definition.getId());
        return model;
    }

    @Override
    public ProcessModel getProcessModel(String id) {
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
            // original is already running and nothing references it yet, so kill it or every
            // failed launch leaks an instance
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
        logger.info("Launched twin {} (original instance {}, twin instance {}) for model {}",
                twin.getId(), original.getProcessInstanceId(), twinInstance.getProcessInstanceId(), modelId);
        return twin;
    }

    @Override
    public TwinProcess getTwinProcess(String id) {
        TwinProcess twin = twinProcesses.get(id);
        if (twin == null) {
            throw new NoSuchElementException("Twin process not found: " + id);
        }
        // recompute - nothing updates status after launch, so the stored one is stale
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

        // replace, don't append - the lookups below use findFirst() so a second link would
        // just be ignored
        twin.getActivityLinks().removeIf(link -> link.getOriginalActivityId().equals(originalActivityId));
        twin.getActivityLinks().add(new ActivityLink(originalActivityId, twinActivityId));
        twin.getEventLog().add("Connected original activity " + originalActivityId
                + " to twin activity " + twinActivityId);
        logger.info("Connected activity {} to twin activity {} on twin process {}",
                originalActivityId, twinActivityId, twinProcessId);
        return twin;
    }

    @Override
    public AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType) {
        // check before we log anything - governance does agentType.trim() and a null blew up
        // as a 500 after the event log had already been written
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }

        TwinProcess twin = getTwinProcess(twinProcessId);

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
        AgentDecision decision = runEvolution(twin, twinProcessId, activityId, agentType);
        // mark it handled so a later bridge doesn't overwrite evolvedAgent_* with its default
        // type. Manual evolve itself isn't gated on this, repeat calls still work.
        if (decision.isApproved()) {
            twin.getForwardedBridgeActivities().add(activityId);
        }
        return decision;
    }

    // Same path as evolveActivity but with no caller to supply an agent type, so it uses the
    // default. Called both by AutoBridgeTrigger (off a live activity-start event) and by the
    // bridge endpoint; the forwarded guard below makes whichever comes second a no-op.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);

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

        if (!twin.getForwardedBridgeActivities().add(activityId)) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " was already forwarded to twin");
            logger.info("Bridge skipped for activity {} on twin {}: already forwarded",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already forwarded to twin");
        }

        String twinActivityId = twin.getActivityLinks().stream()
                .filter(link -> link.getOriginalActivityId().equals(activityId))
                .map(ActivityLink::getTwinActivityId)
                .findFirst()
                .orElse(activityId);

        twin.getEventLog().add("Original activity " + activityId + " reached");
        twin.getEventLog().add("Forwarded event to twin activity " + twinActivityId);
        twin.getEventLog().add("Bridge using default agent type '" + DEFAULT_BRIDGE_AGENT_TYPE
                + "' (no agent type supplied by the triggering event)");
        logger.info("Bridge forwarding activity {} to twin activity {} on twin {} with default agent type {}",
                activityId, twinActivityId, twinProcessId, DEFAULT_BRIDGE_AGENT_TYPE);

        // un-mark on failure, else a governance block or a node manager outage leaves the
        // activity permanently "forwarded" and you can never retry it
        try {
            AgentDecision decision = runEvolution(twin, twinProcessId, activityId, DEFAULT_BRIDGE_AGENT_TYPE);
            if (!decision.isApproved()) {
                twin.getForwardedBridgeActivities().remove(activityId);
            }
            return decision;
        } catch (RuntimeException e) {
            twin.getForwardedBridgeActivities().remove(activityId);
            throw e;
        }
    }

    // Without this the original parks at its first user task forever, and since evolve/bridge
    // both gate on hasReachedActivityInOriginal only that first activity is ever usable.
    // Completes all open tasks, not one named one - a parallel gateway leaves several open at
    // once. Only the original moves; nothing reads the twin's position.
    @Override
    public List<String> completeCurrentTasks(String twinProcessId) {
        TwinProcess twin = getTwinProcess(twinProcessId);

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

        List<String> completed = new ArrayList<>();
        for (Task task : tasks) {
            // definition key == the BPMN activity id, which is what connect/evolve key on
            String label = task.getName() == null
                    ? task.getTaskDefinitionKey()
                    : task.getName() + " (" + task.getTaskDefinitionKey() + ")";
            taskService.complete(task.getId());
            completed.add(label);
        }

        twin.getEventLog().add("Completed " + completed.size()
                + " open user task(s) on original process instance " + twin.getOriginalProcessId()
                + ": " + String.join(", ", completed));
        logger.info("Completed {} open task(s) on original instance {} of twin {}: {}",
                completed.size(), twin.getOriginalProcessId(), twinProcessId, completed);
        return completed;
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

    // shared tail of evolveActivity and bridgeActivityEvent, once both have checked the
    // activity is linked and reached
    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId, String agentType) {
        // governance runs before the node manager - it can deny a type the catalog would allow,
        // and it owns the quota. Reserves rather than checks; released in the finally below.
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

            String twinActivityId = twin.getActivityLinks().stream()
                    .filter(link -> link.getOriginalActivityId().equals(activityId))
                    .map(ActivityLink::getTwinActivityId)
                    .findFirst()
                    .orElse(activityId);

            // this variable is the only real effect an evolution has. If it fails (usually the
            // twin already ended) nothing happened, so don't report approved.
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
            if (!evolutionSucceeded) {
                governanceService.releaseEvolutionSlot(twinProcessId);
            }
        }
    }

}
