package com.metaml.workbench.service;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
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
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    // what a client-supplied model id is allowed to look like. Generated ids are UUIDs, which fit
    // this comfortably; anything with a separator, a dot, or a drive letter in it does not.
    private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9_-]+");

    // still the live copy - WorkbenchStateStore just mirrors these to a file after each change
    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    // twin+visit being evolved right now - the evolvedAgent_* variable alone can't tell you that,
    // since it isn't set until an evolution actually succeeds. Keyed per visit like everything
    // else, or two visits of a multi-instance activity block each other for nothing.
    private final Map<String, Boolean> evolutionsInFlight = new ConcurrentHashMap<>();
    // not persisted across a restart - a generated project is just a directory on disk, and
    // launchGeneratedProject needs its path again; restart already forgets every launched process
    // too, since those don't survive an app restart either
    private final Map<String, GeneratedProject> generatedProjects = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final TwinModelGenerator twinModelGenerator;
    private final WorkbenchStateStore stateStore;
    private final ProcessModelFileStore modelFileStore;
    private final DelegateClassGenerator delegateClassGenerator;
    private final SpringBootProjectGenerator springBootProjectGenerator;
    private final SpringBootProjectLauncher springBootProjectLauncher;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, TwinModelGenerator twinModelGenerator,
            WorkbenchStateStore stateStore, ProcessModelFileStore modelFileStore,
            DelegateClassGenerator delegateClassGenerator, SpringBootProjectGenerator springBootProjectGenerator,
            SpringBootProjectLauncher springBootProjectLauncher) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.twinModelGenerator = twinModelGenerator;
        this.stateStore = stateStore;
        this.modelFileStore = modelFileStore;
        this.delegateClassGenerator = delegateClassGenerator;
        this.springBootProjectGenerator = springBootProjectGenerator;
        this.springBootProjectLauncher = springBootProjectLauncher;
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

    // event log counts as a change too - losing it on restart is a real loss, not just bookkeeping
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
            // The id is client-supplied and ends up as a filename under workbench.models.directory
            // (ProcessModelFileStore.pathFor resolves it straight into that directory), so
            // "../../etc/whatever" or an absolute path would have written the model's BPMN outside
            // the models directory entirely. Checked here rather than only in the file store, and
            // checked before the deploy below, so a rejected id never costs a deploy-then-roll-back
            // round trip through the engine or leaves a deployment behind on the way out.
            if (!SAFE_MODEL_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Process model id may only contain letters, digits, "
                        + "'-' and '_': " + id);
            }
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
        try {
            // the Spring Boot generation step (and Joanna's own spec) needs a real .bpmn file on
            // disk, not just the copy of this XML that WorkbenchStateStore already embeds inside
            // its own shared workbench-state.json - that file is a restart-recovery cache, not
            // something meant to be opened or copied on its own
            modelFileStore.save(modelId, bpmnXml);
        } catch (RuntimeException e) {
            // don't leave a model that's deployed and in memory but has no matching file - roll
            // both back rather than leave a half-saved model the Generate step would silently
            // fail against later
            processModels.remove(modelId, model);
            discardDeployment(deployment.getId());
            throw e;
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

    @Override
    public List<ProcessModel> listProcessModels() {
        return processModels.values().stream()
                .sorted(Comparator.comparing(ProcessModel::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<GeneratedDelegate> generateDelegates(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        return delegateClassGenerator.generate(model.getBpmnXml());
    }

    @Override
    public GeneratedProject generateSpringBootProject(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        // regenerated here rather than reusing generateDelegates' output - that method renders
        // against DelegateClassGenerator's own default package, which is fine for previewing
        // source but not where SpringBootProjectGenerator is about to place the file. Has to be
        // SpringBootProjectGenerator.DELEGATE_PACKAGE specifically, or the class compiles but
        // Spring's component scan never finds it (see that constant's own comment).
        List<GeneratedDelegate> delegates = delegateClassGenerator.generate(model.getBpmnXml(),
                SpringBootProjectGenerator.DELEGATE_PACKAGE);
        GeneratedProject project = springBootProjectGenerator.generate(model.getBpmnXml(), delegates);
        generatedProjects.put(project.projectId(), project);
        logger.info("Generated Spring Boot project {} for model {}", project.projectId(), modelId);
        return project;
    }

    @Override
    public LaunchedProject launchGeneratedProject(String projectId) {
        GeneratedProject project = generatedProjects.get(projectId);
        if (project == null) {
            throw new NoSuchElementException("Generated project not found: " + projectId
                    + " - it may not exist, or the app may have restarted since it was generated");
        }
        return springBootProjectLauncher.launch(project);
    }

    @Override
    public boolean stopGeneratedProject(String projectId) {
        // deliberately not gated on generatedProjects containing it: after a workbench restart that
        // map is empty, and refusing to stop what the launcher is still tracking would leave a
        // running app nothing could reach. The launcher's own registry is the authority on what's
        // running.
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        return springBootProjectLauncher.stop(projectId);
    }

    @Override
    public List<LaunchedProject> listRunningProjects() {
        return springBootProjectLauncher.listRunning();
    }

    // One launch, one twin, and the twin always gets a definition of its own that its token can
    // actually walk. There used to be a second entry point for that (launchProcessWithExecutableTwin)
    // while the passive twin stayed the default; keeping both meant the UI had two buttons that
    // produced twins behaving nothing alike, so this is the only one now.
    @Override
    public TwinProcess launchProcess(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        boolean twinWasAlreadyDeployed = repositoryService.createDeploymentQuery()
                .deploymentName(twinDeploymentName(model)).count() > 0;
        ProcessDefinition twinDefinition = deployTwinDefinition(model);
        try {
            return launch(model, twinDefinition.getId());
        } catch (RuntimeException e) {
            // Only clean up a deployment this call actually made. Duplicate filtering hands back
            // the one an earlier launch created, and a twin from that launch can still be running
            // on it - deleting it cascade-deletes a live instance.
            if (!twinWasAlreadyDeployed) {
                discardDeployment(twinDefinition.getDeploymentId());
            }
            throw e;
        }
    }

    private static String twinDeploymentName(ProcessModel model) {
        return model.getName() + " (twin " + model.getId() + ")";
    }

    // The twin is generated from what's actually deployed rather than from the stored XML, so it
    // can't drift from the definition the original is running.
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
            // Deployment name and resource name both have to be the same on every launch of this
            // model or duplicate filtering has nothing to compare against, hence the model id in
            // both rather than just the display name - two models are allowed to share a name.
            // Without this, launching the same model ten times left ten twin deployments behind,
            // each with its own process definition version, and nothing ever cleaned them up.
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

    // twinDefinitionId is the original's own on the plain path, which is the whole difference
    // between a twin that can move and one that can't
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
        // the twin's own definition, not the original's. The two happen to share activity ids
        // today, so checking the original passed for the wrong reason - and would keep passing for
        // an id the generator had dropped, leaving a link pointing at nothing.
        requireActivityInDefinition(twin.getTwinProcessDefinitionId(), twinActivityId, "twinActivityId");

        // one twin activity, one original activity: evolvedAgent_<twinActivityId> and the twin's own
        // advance message are both keyed on twinActivityId alone, so a second original activity
        // sharing it would silently clobber whatever the first one wrote instead of getting its own
        // slot. Rejected here rather than left to be discovered mid-run.
        //
        // Synchronized on the twin itself - caught by an adversarial review of this very fix:
        // CopyOnWriteArrayList makes each individual list operation thread-safe, but not the
        // check-then-remove-then-add sequence as a whole, so two concurrent calls connecting
        // different originals to the same still-unclaimed twin activity could both pass the check
        // before either one's add() was visible to the other. One lock per twin, not a global one,
        // since only calls racing on the SAME twin can conflict.
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
            // Phase 9/10 red team finding: this and the other operator-actionable skip reasons
            // below used to log at the same INFO level as routine success, indistinguishable from
            // the flood of ordinary per-activity lines - the concrete mechanism behind a twin
            // silently stopping without any louder signal than the human side sailing through with
            // ordinary 200 responses. Bumped to WARN with a stable, greppable prefix.
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
            // runEvolution sets evolvedAgent_<twinActivityId>[_loopCounter] on approval, which is
            // exactly the signal bridgeOnce's alreadyEvolved() checks before letting the auto-bridge
            // (or a repeat manual bridge) stomp this visit with the default agent type - no separate
            // bookkeeping needed here for that to work.
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

    // evolveActivity's path with the default agent type, for callers that have no type of their
    // own. This is the manual Bridge button's entry point: it names an activity, so we work out
    // which visit of it we're talking about before the forwarded guard runs.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        String visitId = currentVisitId(twin, activityId);
        // The button has to move the twin as well as evolve it, and not just for symmetry with the
        // auto trigger: the original's very first activity starts inside startProcessInstanceById,
        // before launchProcess has registered the twin, so the trigger never sees it. If nobody
        // correlated that first message here the twin would sit on it forever and never reach the
        // activities every later step is waiting to find. Safe from here - this runs on a request
        // thread with no engine command around it, unlike the task listener.
        //
        // Only when the original really has been to this activity, though. A null visit means it
        // hasn't, and advancing anyway put the twin a step ahead of the thing it is mirroring.
        // Gating on decision.isApproved() instead would have been wrong: an evolution refused by
        // governance or by the node manager says nothing about where the original's token is, and
        // the twin should still follow it.
        return bridgeAndAdvance(twin, twinProcessId, activityId, visitId);
    }

    // Same activity-instance walk loopCounterOf uses below, but returning the execution id itself
    // rather than the loopCounter already read off it - what the manual bridge button above needs
    // to give advanceTwinActivity's parallel-multi-instance disambiguation the same kind of
    // execution id AutoBridgeTrigger's live ExecutionEvent already provides for free. Null for a
    // plain activity or one the original hasn't reached, same as loopCounterOf's own null cases -
    // advanceTwinActivity treats a null execution id exactly like the auto-bridge path always did.
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

    // AutoBridgeTrigger's entry point: the engine already named the visit that just started. Also
    // the one to call for a SPECIFIC visit of an activity that has more than one open at once (a
    // parallel multi-instance activity) - the two-argument overload's currentVisitId can only ever
    // guess at one, since nothing about "not yet ended" tells two simultaneously-open siblings
    // apart. A caller who already knows which visit they mean - this one, or a future frontend
    // that lets a person pick a specific parallel task off a list - names it directly instead.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        return bridgeAndAdvance(twin, twinProcessId, activityId, activityInstanceId);
    }

    // Phase 9/10 red team finding: advanceTwinActivity used to run unconditionally, after
    // evolutionsInFlight's claim on this visit had already been released - so the LOSER of that
    // claim race (an evolution already in flight elsewhere for the same visit) could still fall
    // through and advance the twin's token, running its automation with evolvedAgent_* unset,
    // while the winner's own evolution later failed outright because the twin execution it was
    // about to write to no longer existed. Reproduced empirically: a manual bridge call arriving
    // while AutoBridgeTrigger's background thread was still mid-flight to the node manager lost the
    // claim, advanced the twin anyway, and the winning evolution's real node-manager round trip was
    // silently discarded. Fixed by holding the SAME claim across both the evolve half and the
    // advance half of one visit, not just the evolve half - a second caller for the identical visit
    // now waits out the whole thing instead of racing past it.
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
                // Caught by an adversarial review: without resolving this, a parallel multi-instance
                // activity with more than one sibling still open would always hit the plain-correlate
                // path below and throw MismatchingMessageCorrelationException, since a caller with no
                // live ExecutionEvent has no execution id to read the way AutoBridgeTrigger does.
                advanceTwinActivity(twinProcessId, activityId,
                        originalExecutionIdForVisit(twin, activityId, activityInstanceId));
            } catch (RuntimeException e) {
                // the bridge itself worked and is already committed, so don't turn it into a
                // failure - advanceTwinActivity has put the reason in the twin's event log already
                logger.warn("Bridged activity {} on twin {} but could not move the twin through it: {}",
                        activityId, twinProcessId, e.toString());
            }
            return decision;
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    // No claim of its own any more - bridgeAndAdvance above holds one claim across both this and
    // the advance that follows it, so a second caller for the same visit never reaches this at all.
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

    // Phase 7 red team finding W4: "already forwarded" used to live only in
    // forwardedBridgeActivities, an in-memory Set that a plain app restart wiped clean - silently
    // reopening every already-bridged visit to a second evolution, since nothing else remembered it
    // had already happened.
    //
    // First version of this fix checked evolvedAgent_<twinActivityId>[_loopCounter] on the twin's
    // own runtime/history state instead, on the reasoning that runEvolution only ever sets it on
    // approval - the same condition the old Set was only added under. An independent adversarial
    // review found that reasoning incomplete: loopCounter only exists for a multi-instance visit, so
    // it correctly makes the variable name visit-unique there (proven by the existing
    // multi-instance bridge tests, untouched by this correction) - but a PLAIN activity revisited
    // through an ordinary BPMN loop-back gateway has no loopCounter at all, and writes that exact
    // same variable name on every visit. The first version saw visit 2 as "already forwarded" the
    // instant visit 1 succeeded - precisely what the deleted Set's own comment had warned about
    // ("one entry per visit, not per activity, or a loop's second time round looks like a
    // duplicate"). Reproduced with a throwaway probe (a loop-back gateway re-entering a plain task)
    // before writing this correction, not assumed; the regression test below records it.
    //
    // Fixed by branching on whether loopCounter is present. When it is, the original check still
    // applies unchanged. When it isn't, this instead compares ORDINAL POSITION: which numbered visit
    // (by start time) this activityInstanceId is on the original side, against how many times
    // evolvedAgent_<twinActivityId> has actually been SET on the twin - both read straight from
    // Camunda's own history, nothing new persisted either way. Ordering by start time is unambiguous
    // for a loop-back specifically because it's a single token going around a cycle - the original
    // cannot start visit 2 before visit 1 has ended. That would not hold for two genuinely
    // concurrent tokens re-entering the same plain activity (for example an inclusive gateway split
    // that loops back into it), which this does not attempt to disambiguate - a known, narrow
    // residual gap, not silently assumed away.
    //
    // Deliberately counting SETS of the variable via HistoricDetail.variableUpdates(), not how many
    // times the twin's automation task has finished - an earlier version of this correction used
    // the automation-finished count and a second independent adversarial review broke it on the
    // existing incident-retry regression test: a failed automation rolls its whole command back
    // (see recordTwinAutomationIncident above), including the automation task's own historic
    // instance, but NOT the evolve step's variable set, which is a separate, already-committed
    // command that ran and succeeded before the automation was ever attempted. Counting automation
    // completions treated a genuinely-already-evolved, automation-still-pending retry as "not yet
    // evolved" and re-ran evolution a second time. Counting SETS of evolvedAgent_<twinActivityId>
    // instead survives that: the first (evolve-succeeded, automation-failed) attempt already left
    // exactly one, so a retry of the SAME visit correctly reads "already evolved," while a genuinely
    // new loop-back visit correctly reads "not yet." Proven empirically with a throwaway probe
    // (ZzHistoricDetailProbeTest, deleted after confirming) that setting one variable name twice on
    // a process instance produces two distinct HistoricVariableUpdate rows, in order, not one
    // collapsed to the latest value the way HistoricVariableInstance would.
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
            // shouldn't happen - the caller already resolved this activityInstanceId from the same
            // history - but treat "can't place this visit" as "not yet evolved" rather than guess
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

    // durable across restarts by construction - a row in the engine's own tables, not app memory -
    // the same "shared Camunda runtime is the source of truth" invariant everything here depends on.
    // Falls back to history for a twin that has since ended, where runtimeService has nothing left
    // to read.
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

    // The twin's copy of an activity is a receive task waiting on a message of its own, so the
    // twin's token arrives and stops there. Correlating that message is what runs the project's
    // automation, on its service task, and carries the token on to whatever comes next - which in
    // turn stops at the message the next step of this will be looking for.
    //
    // Nothing here is an error worth shouting about. Gateways and end events have no message of
    // their own, an activity nobody connected has no twin activity to look up, and an original
    // that has walked past the point its twin is waiting at has nothing to correlate either.
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

        // Parallel multi-instance can have more than one twin sibling waiting on the identical
        // message at once - plain and sequential activities never do, so this only ever resolves
        // to something when there's genuinely more than one candidate to choose between.
        String messageName = TwinModelGenerator.twinMessageName(twinActivityId);
        String parallelSiblingExecutionId = originalExecutionId == null ? null
                : resolveParallelSibling(twin, messageName, originalExecutionId);

        // Asking first rather than letting correlate() throw MismatchingMessageCorrelationException:
        // the twin sitting somewhere else is the ordinary case on nearly every trigger, and it
        // isn't worth an exception and a stack trace each time.
        if (parallelSiblingExecutionId == null && !isTwinWaitingAt(twin, twinActivityId)) {
            return TwinAdvance.skipped(twinActivityId,
                    "Twin activity " + twinActivityId + " is not waiting to be advanced");
        }

        // Its own budget, deliberately not reserveEvolutionSlot's. An evolution is a request out
        // to the node manager for an agent; this is the twin taking one step, which happens for
        // every activity it passes. Sharing the counter would have ordinary automation eating the
        // quota that exists to limit agent requests.
        //
        // Reserved after the waiting check rather than before it so the gateways and end events the
        // trigger fires for, which have nothing to advance, don't spend anything.
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
                // targets one named execution directly, bypassing correlate()'s ambiguity - the
                // only way proven to release exactly one parallel sibling and leave the rest
                // waiting, since correlate() throws the instant more than one execution matches
                runtimeService.messageEventReceived(messageName, parallelSiblingExecutionId);
            } else {
                // scoped to this instance, so a second twin on the same definition waiting at the
                // same activity is not a candidate and correlate() never has to pick between them
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

    // A real, Cockpit-visible Incident - not a log line nobody's watching - and deliberately not
    // tied to any job: the twin's automation Service Task is synchronous on purpose, so by the
    // time this runs Camunda has already rolled the whole failed messageEventReceived()/
    // correlate() command back, and the twin's receive task sits exactly where it did before the
    // failure - untouched, its event subscription intact, safe to retry later by simply
    // re-bridging the same activity. Proven empirically (a throwaway probe that made an automation
    // delegate throw, confirmed the subscription survived unchanged, then manually created an
    // incident against that same execution, resolved it, and retried the identical correlation
    // successfully) before writing this, not assumed.
    //
    // Deliberately NOT a retry loop. Automation is a per-project pluggable extension point
    // (ProjectAutomationService), and only a specific implementation can know whether its own
    // failures are safe to retry blindly - execute() carries no documented idempotency contract,
    // and a blanket retry here could double-invoke something that charges a quota or calls an
    // external agent with a real side effect. Surfacing a resolvable incident and leaving the twin
    // exactly where it was is the smallest mechanism that fits without guessing at that contract;
    // a project whose automation genuinely needs bounded retry for a known-transient dependency
    // should implement that inside its own ProjectAutomationService, not here.
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
            // the original failure is still the one that matters and is already logged/rethrown by
            // the caller - losing the incident record isn't worth masking it with a different one
            logger.warn("Could not record an incident for twin activity {} on twin {}: {}",
                    twinActivityId, twin.getId(), incidentFailure.toString());
            return;
        }
        logger.warn("Twin activity {} on twin {} failed to execute; incident recorded on execution {}: {}",
                twinActivityId, twin.getId(), executionId, failure.toString());
    }

    // Same "which execution is this activity id actually waiting on" question isTwinWaitingAt
    // already answers as a boolean - this keeps the execution id instead of throwing it away, for
    // attaching an incident to the right place when no more specific one is already known.
    //
    // Deliberately the ActivityInstance tree, the same way loopCounterOf/originalExecutionIdForVisit
    // already resolve an execution for a specific activity, not createExecutionQuery() +
    // getActiveActivityIds() the way isTwinWaitingAt checks. Found the hard way: that check can
    // return a scope execution that merely sees the activity through a descendant (its own
    // getActiveActivityIds() aggregates children), without the scope execution itself being
    // positioned there - createIncident rejects exactly that with "activity is null", since it
    // needs the actual leaf, not anything that can see it.
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

    // Resolves which of possibly several twin siblings waiting on the same message corresponds to
    // the original's own execution that just started - by loopCounter, since both sides create
    // their multi-instance children in the same deterministic order for the same cardinality.
    // Camunda resolves a non-local getVariable() up the scope chain, which is what actually finds
    // loopCounter here: it's local to the per-iteration scope execution, one level above whichever
    // execution holds the event subscription, and the plain (local-only) read misses it entirely -
    // proven empirically before writing this, not assumed. Returns null when there's nothing to
    // disambiguate (a plain or sequential activity, where at most one candidate is ever waiting),
    // so isTwinWaitingAt's existing single-candidate path is untouched for those.
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

    // getActiveActivityIds rather than an activityId() execution query: inside a sequential
    // multi-instance the token sits on a child execution, and the query would only match if we
    // already knew which one to ask.
    private boolean isTwinWaitingAt(TwinProcess twin, String twinActivityId) {
        for (Execution execution : runtimeService.createExecutionQuery()
                .processInstanceId(twin.getTwinProcessId()).list()) {
            if (runtimeService.getActiveActivityIds(execution.getId()).contains(twinActivityId)) {
                return true;
            }
        }
        return false;
    }

    // without this the original parks at its first task forever and evolve/bridge never unblock
    // completes everything open, not one named task - a parallel gateway leaves several
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

        // each complete() is its own transaction, so this list can go stale mid-loop (a second
        // request on the same twin, a branch finishing and taking its siblings with it) - task 3
        // blowing up used to throw away that tasks 1 and 2 really did complete
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

    // Everything that guards against doing an activity twice keys on Camunda's activity instance
    // id, because a loop or multi-instance activity comes round again under the same activity id.
    // Callers that only know the activity id (the manual Bridge button, manual Evolve) have to
    // resolve the same id here or their guard is looking at a different namespace than the
    // auto-bridge's. Null means the original never got there.
    private String currentVisitId(TwinProcess twin, String activityId) {
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .orderByHistoricActivityInstanceStartTime().desc()
                .list();
        // one it's sitting on right now is what the button means. if it already walked past,
        // the newest finished visit is the closest thing to what the caller is pointing at.
        for (HistoricActivityInstance visit : visits) {
            if (visit.getEndTime() == null) {
                return visit.getId();
            }
        }
        return visits.isEmpty() ? null : visits.get(0).getId();
    }

    // AgentExecutionDelegate reads loopCounter straight off the execution it's completing; over
    // here all we have is the visit, so go the long way round to the same value. Null for a plain
    // activity, which is what keeps its variable name short.
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
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, loopCounter);
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

            // this variable is the only real effect an evolution has. if it doesn't land
            // (usually the twin already ended) then nothing happened, so don't say approved.
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

            evolutionSucceeded = true;
            AgentDecision decision = new AgentDecision(agentType, true, availability.getAgentName(),
                    availability.getReason(), availability.isRiskFlagged());
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

    // Has to be reconciled both ways, not just written. Re-evolving Task_Credit with an ordinary
    // agent after a credit-risk-assessor run left the old risk flag sitting there otherwise, and
    // the process kept escalating even though the twin now showed a plain agent with nothing
    // wrong. Which outputs the last evolution left behind isn't something you can work out from
    // the current ones, hence the index variable alongside them.
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

}
