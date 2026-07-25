package com.metaml.workbench.service;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
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
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkbenchServiceImpl implements WorkbenchService {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchServiceImpl.class);

    // Used when the bridge auto-forwards a reached activity with no caller-supplied agent
    // type -- there's no human in that loop to ask, so it forwards with the one agent type
    // the demo's node manager catalog always recognizes.
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
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
            // A caller-supplied id used to silently replace an existing model (and its
            // processDefinitionId) while already-launched twins kept pointing at the old
            // definition, so a later "Load model" would show XML the running twin never used.
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
            // singleResult() throws when the deployment produced more than one process
            // definition, i.e. the XML declares several executable bpmn:process elements.
            // Same class of caller mistake as invalid XML above, so same 400, not a 500.
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
            // The original is already running by this point and no TwinProcess has been
            // recorded yet, so without this rollback a failed launch would leak an instance
            // nothing references -- and every retry would leak another one.
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
        // Recomputed on every read rather than trusted from launch time: nothing ever writes
        // status again after launch, so the stored "RUNNING" would still be reported long
        // after both instances ended -- and GET /twin/{id} is the artifact the demo points at.
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

        // Re-connecting the same original activity replaces its link instead of appending a
        // second one: runEvolution/bridgeActivityEvent resolve the twin activity with
        // findFirst(), so an appended correction would be silently ignored in favour of the
        // stale first link.
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
        // Checked before anything is logged or reserved: governance's denylist match does
        // agentType.trim(), so a missing agentType used to surface as an NPE/500 from deep
        // inside the governance layer after the twin's event log had already been written to.
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
        // A successful manual evolve counts as this activity having been handled, same as a
        // successful bridge forward -- otherwise a later bridge call for the same twin+activity
        // wouldn't know a human already evolved it, and would silently overwrite the twin's
        // evolvedAgent_<activityId> variable with its own default agent type. Manual evolve itself
        // is never gated on this marker (repeated manual calls, e.g. up to the governance quota,
        // must keep working exactly as before).
        if (decision.isApproved()) {
            twin.getForwardedBridgeActivities().add(activityId);
        }
        return decision;
    }

    // The automatic counterpart to evolveActivity: instead of a human/frontend supplying
    // twinProcessId+activityId+agentType directly, this checks whether the original process
    // has actually reached an activity that has a twin link, and if so forwards it through
    // the same governance/node-manager path evolveActivity uses -- with a default agent type,
    // since there's no caller here to ask for one. Called by the bridge REST endpoint, which
    // is polled/triggered after launch+connect rather than wired to a live Camunda listener
    // (see WorkbenchController for why: a listener would need to be attached per-deployment
    // to arbitrary user-authored BPMN, which is more moving parts than this demo needs).
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

        // The forwarded-marker reserved above must only stick if evolution actually succeeded --
        // otherwise a governance block, an unavailable agent, or a node-manager outage would
        // permanently mark this activity as forwarded despite nothing having gone through, making
        // it unretryable even after the underlying problem is fixed.
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

    // Shared by evolveActivity and bridgeActivityEvent once both have already confirmed the
    // activity is linked and actually reached: governance check, node manager check, and
    // setting the twin's process variable are identical either way.
    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId, String agentType) {
        // Reserved before the node manager is even contacted: governance can deny an agent
        // type the node manager's own catalog would otherwise allow (policy denylist), or
        // block once a twin process has used up its evolution quota -- a constraint the node
        // manager has no concept of. The quota slot is reserved (not just checked) here, and
        // released in the finally block below if the evolution doesn't end up succeeding --
        // see GovernanceServiceImpl.reserveEvolutionSlot for why check-then-record separately
        // would race under concurrent requests.
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

            // Writing this variable is the only real effect an evolution has on the twin. If it
            // fails (typically because the twin instance has already ended) nothing actually
            // happened, so the decision must NOT be reported as approved: an approved-but-no-op
            // result would consume a governance quota slot, mark the activity as forwarded, and
            // tell the caller an agent was assigned when none was.
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
