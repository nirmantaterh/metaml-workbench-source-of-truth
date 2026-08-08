package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.workbench.service.WorkbenchServiceImpl;
import com.metaml.workbench.store.WorkbenchStateStore;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// full walkthrough against a real embedded engine - only the node manager is stubbed
// mem db, not the file one the app uses - same url as WbapiApplicationTests so they share a context
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1",
        "workbench.state.persist=false",
        "workbench.models.directory=./target/test-data/models",
        "workbench.generation.template-directory=../../templates/camundademo",
        "workbench.generation.output-directory=./target/test-data/generated-projects"
})
class WireTransferWalkthroughTest {

    private static final String KYC = "Task_KYC";
    private static final String AML = "Task_AML";
    private static final String OFAC = "Task_OFAC";
    private static final String CREDIT = "Task_Credit";
    private static final String ESCALATE = "Task_Escalate";
    private static final String APPROVE = "Task_Approve";
    private static final String EXECUTE = "Task_Execute";
    private static final String NOTIFY = "Task_Notify";

    // what the bridge picks when no caller supplied a type, and what the real catalog answers
    private static final String BRIDGE_AGENT = "validator-agent-01";

    // the one catalog entry that comes back with a flag raised, and the variable that flag turns
    // into on the original once the delegate has run
    private static final String RISK_AGENT_TYPE = "credit-risk-assessor";
    private static final String RISK_FLAG = "agentFlaggedRisk";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private TwinModelGenerator twinModelGenerator;
    @Autowired
    private WorkbenchStateStore stateStore;
    @Autowired
    private com.metaml.workbench.store.ProcessModelFileStore modelFileStore;
    @Autowired
    private com.metaml.workbench.codegen.DelegateClassGenerator delegateClassGenerator;
    @Autowired
    private com.metaml.workbench.generation.SpringBootProjectGenerator springBootProjectGenerator;
    @Autowired
    private com.metaml.workbench.generation.SpringBootProjectLauncher springBootProjectLauncher;

    @BeforeEach
    void stubTheCatalogAndOpenTheQuota() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            // same rule the real NodeManagerServiceImpl catalog uses: only the credit assessor
            // ever comes back flagged
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    RISK_AGENT_TYPE.equals(type));
        });
        // seven activities get bridged on one twin below, well past the default cap of 5, so
        // the quota needs raising first.
        governanceService.updatePolicy(Set.of(), 20);
    }

    // Joanna's new scope doc, item 2 (Project Saving): the Generate/Spring-Boot-Generation step
    // needs a real .bpmn file on the server filesystem it can copy into a generated project, not
    // just the copy of the XML that WorkbenchStateStore already embeds inside its own shared
    // workbench-state.json. Proven end to end here, through the real service, not just against
    // ProcessModelFileStore in isolation (that's covered separately in
    // com.metaml.workbench.store.ProcessModelFileStoreTest).
    @Test
    void savingAModelWritesItsBpmnAsARealFileOnTheServerFilesystem() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "file store test", citibankBpmn());

        assertThat(modelFileStore.exists(model.getId())).isTrue();
        String onDisk = java.nio.file.Files.readString(modelFileStore.pathFor(model.getId()));
        assertThat(onDisk).isEqualTo(citibankBpmn());
    }

    // The id on a save request is client-supplied, and it becomes a filename under
    // workbench.models.directory. Rejected up front, before the deploy, so a traversal attempt
    // can't even leave a deployment behind on its way out - and asserting the throw on its own
    // would be a weak test here, since the whole risk is a file appearing somewhere it shouldn't,
    // so this checks the target directory is genuinely still empty afterwards.
    @Test
    void aTraversalShapedModelIdIsRejectedAndWritesNothingOutsideTheModelsDirectory() throws IOException {
        Path modelsDir = Path.of("./target/test-data/models").toAbsolutePath().normalize();
        Path escapeTarget = modelsDir.getParent().resolve("escaped.bpmn");
        Files.createDirectories(modelsDir);
        Files.deleteIfExists(escapeTarget);

        assertThatThrownBy(() -> workbenchService.saveProcessModel("../escaped", "traversal", citibankBpmn()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may only contain letters, digits");
        assertThatThrownBy(() -> workbenchService.saveProcessModel(
                modelsDir.getParent().resolve("absolute-escaped").toString(), "traversal", citibankBpmn()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may only contain letters, digits");

        assertThat(escapeTarget).doesNotExist();
        assertThat(modelsDir.getParent().resolve("absolute-escaped.bpmn")).doesNotExist();
        // and nothing landed inside the directory under a mangled name either
        try (var entries = Files.list(modelsDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.contains("escaped"));
        }
    }

    // New scope item 1 (Navigation & UI): "Edit Existing Project" needs a real list to pick from.
    @Test
    void listProcessModelsReturnsEveryModelNewestFirst() throws IOException {
        ProcessModel first = workbenchService.saveProcessModel(null, "first saved", citibankBpmn());
        ProcessModel second = workbenchService.saveProcessModel(null, "second saved", loanApprovalBpmn());

        List<ProcessModel> models = workbenchService.listProcessModels();

        assertThat(models).extracting(ProcessModel::getId).contains(first.getId(), second.getId());
        int firstIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(first.getId())).findFirst().get());
        int secondIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(second.getId())).findFirst().get());
        // second was saved after first, so it should come back before it
        assertThat(secondIndex).isLessThan(firstIndex);
    }

    // New scope item 3 (BPMN Processing), proven through the real saved-model path rather than
    // against DelegateClassGenerator in isolation (that's covered separately in
    // com.metaml.workbench.codegen.DelegateClassGeneratorTest). Neither example model in this repo
    // exercises this - both use user tasks with a taskListener delegateExpression
    // (agentExecutionDelegate) for agent execution, not a service task with a direct
    // delegateExpression the way Joanna's own example does - so this needs its own small fixture.
    @Test
    void generateDelegatesReadsTheRealSavedModelNotJustARawXmlString() {
        ProcessModel model = workbenchService.saveProcessModel(null, "delegate generation test",
                loanApprovalBpmn());

        List<com.metaml.workbench.codegen.GeneratedDelegate> generated =
                workbenchService.generateDelegates(model.getId());

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).beanName()).isEqualTo("calculateInterestService");
        assertThat(generated.get(0).className()).isEqualTo("CalculateInterestService");
    }

    // New scope item 4 (Spring Boot Generation), proven through the real saved-model path against
    // the real template on disk - not the synthetic fixture SpringBootProjectGeneratorTest uses -
    // so this is the one place that would have caught the delegate package/directory mismatch bug
    // (see DelegateClassGenerator.DEFAULT_PACKAGE's own comment) if the earlier fix hadn't already
    // been proven by a real mvn compile of a generated project.
    @Test
    void generateSpringBootProjectProducesADelegateWhosePackageMatchesWhereItsActuallyWritten() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "project generation test",
                loanApprovalBpmn());

        com.metaml.workbench.generation.GeneratedProject project =
                workbenchService.generateSpringBootProject(model.getId());

        assertThat(project.processKey()).isEqualTo("loanApproval");
        java.nio.file.Path delegateFile = project.directory().resolve(
                "src/main/java/com/example/camundademo/delegates/CalculateInterestService.java");
        assertThat(delegateFile).exists();
        assertThat(java.nio.file.Files.readString(delegateFile))
                .contains("package com.example.camundademo.delegates;");
        assertThat(project.directory().resolve("src/main/resources/processes/loanApproval.bpmn")).exists();
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_LoanApproval" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="StartEvent_1" name="Loan Request Received">
                      <bpmn2:outgoing>SequenceFlow_1</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}">
                      <bpmn2:incoming>SequenceFlow_1</bpmn2:incoming>
                      <bpmn2:outgoing>SequenceFlow_2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="SequenceFlow_1" sourceRef="StartEvent_1" targetRef="ServiceTask_1" />
                    <bpmn2:endEvent id="EndEvent_1" name="Loan Approved">
                      <bpmn2:incoming>SequenceFlow_2</bpmn2:incoming>
                    </bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="SequenceFlow_2" sourceRef="ServiceTask_1" targetRef="EndEvent_1" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    @Test
    void walksTheWireTransferFromKycToTheEnd() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getOriginalProcessId()).isNotBlank();
        assertThat(twin.getTwinProcessId()).isNotBlank().isNotEqualTo(twin.getOriginalProcessId());
        assertThat(twin.getStatus()).isEqualTo("RUNNING");
        assertThat(openActivities(twin)).containsExactly(KYC);

        // KYC is the one you bridge by hand. Its start event fires inside startProcessInstanceById,
        // before launchProcess has put the twin in the map, so the trigger has nothing to look up.
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(kyc.getAgentName()).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, KYC)).isEqualTo(BRIDGE_AGENT);

        // connect before completing. the start event for an activity fires once and doesn't come
        // back, so anything connected afterwards needs the manual bridge button instead.
        connect(twin, AML, OFAC, CREDIT);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // the "complete" task listener on Task_KYC fires here, on the real (original) instance
        // finishing the task - proves the agent execution delegate actually ran, not just deployed
        assertThat(agentExecuted(twin, KYC)).isEqualTo(BRIDGE_AGENT);
        // and it's in the event log, so the UI shows it like every other operation
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("agentExecuted_" + KYC));

        // genuine parallel split - three tasks open at the same time, not one after another
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);

        // nobody called bridge for any of these three. this is the whole point of the trigger.
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, OFAC)).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, CREDIT)).isEqualTo(BRIDGE_AGENT);

        connect(twin, APPROVE, EXECUTE, NOTIFY);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(openActivities(twin)).containsExactly(APPROVE);
        assertThat(evolvedAgent(twin, APPROVE)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly(EXECUTE);
        assertThat(evolvedAgent(twin, EXECUTE)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly(NOTIFY);
        assertThat(evolvedAgent(twin, NOTIFY)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).isEmpty();
        assertThat(reached(twin, "EndEvent_Success")).isTrue();
        // never went down either rejection branch or the approval timeout
        assertThat(reached(twin, "EndEvent_RejectedIdentity")).isFalse();
        assertThat(reached(twin, "EndEvent_RejectedCompliance")).isFalse();
        assertThat(reached(twin, "Task_EscalateTimeout")).isFalse();

        // This used to assert TWIN_RUNNING_ORIGINAL_ENDED, back when the twin was a second copy of
        // the human process and sat on its own KYC task forever. Launch gives the twin a generated
        // definition of its own now, so it walked the same route and finished a step ahead of the
        // original - the twin does an activity when the original reaches it, not when it leaves it.
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus()).isEqualTo("ENDED");
        assertThat(twinReached(twin, KYC)).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelJoin")).isTrue();
        assertThat(twinReached(twin, "EndEvent_Success")).isTrue();
        assertThat(twinAutomation(twin, NOTIFY)).isNotNull();

        // seven activities, seven slots. a double-count would show up here before anywhere else.
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(7);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).isEmpty();
    }

    // up to now an evolution was pure bookkeeping - it recorded which agent was picked and the
    // original ran exactly the same either way. Task_Credit is the one activity where the agent's
    // answer steers the process, so these two runs differ in nothing but which agent evolved it.
    @Test
    void aRiskFlaggingAgentSendsTheTransferToTheComplianceOfficer() throws IOException {
        TwinProcess plain = walkToTheComplianceChecks("citi wire transfer plain credit check");
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(3);

        assertThat(originalVariable(plain, RISK_FLAG)).isNull();
        assertThat(openActivities(plain)).containsExactly(APPROVE);
        assertThat(reached(plain, ESCALATE)).isFalse();
        // and out the far end the way it always did
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(reached(plain, "EndEvent_Success")).isTrue();

        TwinProcess flagged = walkToTheComplianceChecks("citi wire transfer risk assessed credit check");
        AgentDecision credit = workbenchService.evolveActivity(flagged.getId(), CREDIT, RISK_AGENT_TYPE);
        assertThat(credit.isApproved()).isTrue();
        assertThat(credit.isRiskFlagged()).isTrue();
        assertThat(evolvedAgent(flagged, CREDIT)).isEqualTo(RISK_AGENT_TYPE + "-agent-01");

        assertThat(workbenchService.completeCurrentTasks(flagged.getId())).hasSize(3);

        assertThat(originalVariable(flagged, RISK_FLAG)).isEqualTo(true);
        assertThat(openActivities(flagged)).containsExactly(ESCALATE);
        assertThat(reached(flagged, APPROVE)).isFalse();
    }

    // caught by an adversarial review pass, not written test-first: evolving with an ordinary
    // agent after a flagged one left the old flag sitting on the twin, so the UI would show a
    // plain agent assigned while the process kept escalating anyway
    @Test
    void reEvolvingWithAnOrdinaryAgentClearsAnEarlierRiskFlag() throws IOException {
        TwinProcess twin = walkToTheComplianceChecks("citi wire transfer re-evolved credit check");

        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, RISK_AGENT_TYPE).isRiskFlagged()).isTrue();
        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, "validator").isRiskFlagged()).isFalse();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(originalVariable(twin, RISK_FLAG)).isNull();
        assertThat(openActivities(twin)).containsExactly(APPROVE);
        assertThat(reached(twin, ESCALATE)).isFalse();
    }

    // the shared front half of both runs above: launch, bridge KYC by hand, and stop with the
    // three compliance checks open and connected
    private TwinProcess walkToTheComplianceChecks(String modelName) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, modelName, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML, OFAC, CREDIT);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        return twin;
    }

    // connect() above always maps id to itself, so it never catches original/twin ids differing
    @Test
    void agentExecutionResolvesThroughANonIdentityActivityLink() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer mismatched link", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(agentExecuted(twin, KYC)).isEqualTo(BRIDGE_AGENT);
    }

    // the auto-bridge keys its already-forwarded guard on the activity instance, the manual button
    // used to key on the bare activity id, so the two guards lived in namespaces that could never
    // match and a redundant click bought a second quota slot
    @Test
    void manualBridgeAfterTheAutoBridgeChangesNothing() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer double bridge",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        int used = governanceService.getUsage(twin.getId()).getEvolutionCount();
        AgentDecision again = workbenchService.bridgeActivityEvent(twin.getId(), AML);

        assertThat(again.isApproved()).isFalse();
        assertThat(again.getReason()).contains("already forwarded");
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(used);
    }

    // Phase 7 red team finding W4, second correction: caught by an independent adversarial review
    // of the first fix, which checked evolvedAgent_<twinActivityId>[_loopCounter] on the twin's
    // runtime/history state - correct for a multi-instance visit, since loopCounter makes the name
    // visit-unique there (the test right above this one still proves that), but wrong for a PLAIN
    // activity revisited through an ordinary loop-back gateway: no multi-instance, no loopCounter,
    // every visit writes the identical variable name. That made visit #2 look "already forwarded"
    // the instant visit #1 succeeded - exactly what the deleted forwardedBridgeActivities Set's own
    // comment had warned about.
    @Test
    void aPlainActivityRevisitedThroughALoopBackGatewayBridgesEveryVisitNotJustTheFirst() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "loop back gateway test", loopBackBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Redo", "Task_Redo");

        // visit #1's start event fires during launchProcess, before the twin is tracked - same
        // reason KYC gets bridged by hand elsewhere in this file
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Redo").isApproved()).isTrue();
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-01");

        Task firstVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        taskService.complete(firstVisit.getId(), Map.of("redo", true));

        // completing visit #1 with redo=true sends the token back through the gateway into the
        // same activity a second time - same activityId, no loopCounter, a brand new activityInstanceId
        Task secondVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        assertThat(secondVisit.getId()).isNotEqualTo(firstVisit.getId());

        // the bug: visit #2's evolution used to be silently skipped as "already forwarded" here
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-02");

        taskService.complete(secondVisit.getId(), Map.of("redo", false));
        assertThat(reached(twin, "EndEvent_1")).isTrue();
    }

    // Phase 7 red team finding W4: the old forwardedBridgeActivities guard lived only in
    // WorkbenchServiceImpl's own memory, so a plain app restart wiped it and reopened every
    // already-bridged visit to a second evolution. It's gone now - the guard is derived from
    // evolvedAgent_<twinActivityId> on the twin's own Camunda runtime state instead, which is a row
    // in the engine's tables, not app memory. Proven here with a second WorkbenchServiceImpl built
    // directly rather than through Spring: its own twinProcesses map starts with nothing but the
    // TwinProcess object itself (id, activity links - exactly what restoreState() reconstructs from
    // the state file on a real reboot), no evolutionsInFlight claim and no per-visit dedup state of
    // any kind carried over, because TwinProcess no longer has anywhere to carry one. If the
    // duplicate is still refused here, it can only be because the check reads Camunda's own tables.
    @Test
    void bridgeDedupeIsSafeAcrossACompletelyFreshServiceInstance() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "restart dedupe test", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        AgentDecision firstBridge = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(firstBridge.isApproved()).isTrue();

        WorkbenchServiceImpl freshService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                runtimeService, repositoryService, historyService, taskService, twinModelGenerator, stateStore,
                modelFileStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher);
        Field twinProcessesField = WorkbenchServiceImpl.class.getDeclaredField("twinProcesses");
        twinProcessesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TwinProcess> freshTwinProcesses = (Map<String, TwinProcess>) twinProcessesField.get(freshService);
        freshTwinProcesses.put(twin.getId(), twin);

        AgentDecision secondBridge = freshService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(secondBridge.isApproved()).isFalse();
        assertThat(secondBridge.getReason()).contains("already forwarded");
        // exactly the one call the first, genuine bridge made - the fresh instance never asked again
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1))
                .checkAgentAvailability(anyString());
    }

    // the delegate used to read the twin's variables blind and let the engine throw. the catch
    // around it never helped: the transaction is rollback-only by then, so completing the task
    // failed with UnexpectedRollbackException and the real cause only showed up as a warn line
    @Test
    void completingATaskStillWorksAfterTheTwinInstanceIsGone() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer lost twin",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();

        runtimeService.deleteProcessInstance(twin.getTwinProcessId(), "twin ended before the original");
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus())
                .isEqualTo("ORIGINAL_RUNNING_TWIN_ENDED");

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        assertThat(agentExecuted(twin, KYC)).isNull();
    }

    // the delegate looked the twin activity up with a resolver that fell back to the activity's
    // own id, so completing an activity nobody connected read whatever agent another link had
    // parked under that name and reported it as executed
    @Test
    void anUnconnectedActivityNeverReportsAnAgentExecution() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer unconnected",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        // Task_AML is one of the three that just opened, and it was never connected to anything
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(agentExecuted(twin, AML)).isNull();
    }

    @Test
    void completingTwiceAtOnceDoesNotBlowUp() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer race", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).hasSize(3);

        // both requests take their task snapshot before either one completes anything, which is
        // what a double click on Complete current task(s) does at the compliance-check step
        CyclicBarrier gate = new CyclicBarrier(2);
        Callable<List<String>> complete = () -> {
            gate.await(10, TimeUnit.SECONDS);
            return workbenchService.completeCurrentTasks(twin.getId());
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<String>>> results = pool.invokeAll(List.of(complete, complete));
            List<String> all = new ArrayList<>();
            for (Future<List<String>> result : results) {
                all.addAll(result.get(30, TimeUnit.SECONDS));
            }
            // each task completed exactly once, split between the two however it landed
            assertThat(all).hasSize(3);
        } finally {
            pool.shutdownNow();
        }

        assertThat(openActivities(twin)).containsExactly(APPROVE);
    }

    // stubbed catalog parks whoever gets in first, guaranteeing the second one arrives mid-evolution
    @Test
    void evolveAndBridgeAtOnceOnlyBurnOneSlot() throws Exception {
        assertOneSlotWhenRacing(true);
        assertOneSlotWhenRacing(false);
    }

    private void assertOneSlotWhenRacing(boolean evolveGoesFirst) throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer evolve race " + evolveGoesFirst, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch secondIsDone = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean(false);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            if (parked.compareAndSet(false, true)) {
                firstIsInside.countDown();
                secondIsDone.await(20, TimeUnit.SECONDS);
            }
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        Callable<AgentDecision> first = evolveGoesFirst
                ? () -> workbenchService.evolveActivity(twin.getId(), KYC, "validator")
                : () -> workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        Callable<AgentDecision> second = evolveGoesFirst
                ? () -> workbenchService.bridgeActivityEvent(twin.getId(), KYC)
                : () -> workbenchService.evolveActivity(twin.getId(), KYC, "validator");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AgentDecision> firstResult = pool.submit(first);
            assertThat(firstIsInside.await(20, TimeUnit.SECONDS)).isTrue();
            AgentDecision secondDecision = second.call();
            secondIsDone.countDown();
            AgentDecision firstDecision = firstResult.get(30, TimeUnit.SECONDS);

            assertThat(firstDecision.isApproved()).isTrue();
            assertThat(secondDecision.isApproved()).isFalse();
        } finally {
            pool.shutdownNow();
        }

        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(1);
    }

    // Phase 9/10 red team finding: advanceTwinActivity used to run unconditionally once the
    // evolutionsInFlight claim was released, so the LOSER of two concurrent bridge calls for the
    // identical visit could advance the twin's token before the WINNER's own evolution had actually
    // finished - running automation with evolvedAgent_KYC still unset, and losing the winner's real
    // node-manager round trip outright when its later setVariable() found the twin already moved on.
    @Test
    void aSecondConcurrentBridgeForTheSameVisitNeverAdvancesBeforeTheFirstEvolutionFinishes() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer advance race", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean(false);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            if (parked.compareAndSet(false, true)) {
                firstIsInside.countDown();
                releaseFirst.await(20, TimeUnit.SECONDS);
            }
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AgentDecision> firstCall = pool.submit(() -> workbenchService.bridgeActivityEvent(twin.getId(), KYC));
            assertThat(firstIsInside.await(20, TimeUnit.SECONDS)).isTrue();

            Future<AgentDecision> secondCall = pool.submit(() -> workbenchService.bridgeActivityEvent(twin.getId(), KYC));
            // loses the claim immediately and returns fast - the fix means it never even attempts
            // to advance the twin, rather than blocking until the winner finishes
            AgentDecision secondDecision = secondCall.get(10, TimeUnit.SECONDS);
            assertThat(secondDecision.isApproved()).isFalse();

            // the proof the bug is fixed: at this exact point, with the winning evolution still
            // parked mid-flight, the twin must NOT have been advanced by the loser, and no agent
            // must have been recorded yet either
            assertThat(runtimeService.getActiveActivityIds(twin.getTwinProcessId())).containsExactly(KYC);
            assertThat(evolvedAgent(twin, KYC)).isNull();

            releaseFirst.countDown();
            AgentDecision firstDecision = firstCall.get(30, TimeUnit.SECONDS);

            assertThat(firstDecision.isApproved()).isTrue();
            assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
            assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(1);
            assertThat(twinAutomation(twin, KYC)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    // forwardedBridgeActivities used to key on activityId alone, so visit #2 looked like a
    // duplicate. Then the guard got fixed but the variables didn't: both visits still wrote
    // evolvedAgent_Task_Loop, so visit #2 quietly overwrote visit #1 and two real evolutions
    // were indistinguishable from one.
    @Test
    void multiInstanceActivityBridgesEveryVisitNotJustTheFirst() throws IOException {
        // a different agent per call, so a variable that survived from the wrong visit shows up
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "loop task test", loopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Loop", "Task_Loop");

        // visit #1's start event fires during launchProcess, before the twin is tracked - same
        // reason KYC gets bridged by hand elsewhere in this file
        AgentDecision firstVisit = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Loop");
        assertThat(firstVisit.isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // completing visit #1 opens visit #2 - same activityId, different activityInstanceId
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);

        // both evolutions still there at the end, each with the agent its own visit was given
        assertThat(evolvedAgent(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        assertThat(evolvedAgent(twin, "Task_Loop_1")).isEqualTo("validator-agent-02");
        assertThat(agentExecuted(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        // the unsuffixed name is what the old one-per-activity write used
        assertThat(evolvedAgent(twin, "Task_Loop")).isNull();
        assertThat(agentExecuted(twin, "Task_Loop")).isNull();

        // The twin walked both visits of its own copy of the loop, one message per visit, and its
        // second visit is bridged the moment the original's second visit opens - which is before
        // the original completes it. So the twin has already reached its end event by the time
        // AgentExecutionDelegate runs for visit #2, and the delegate leaves a finished twin alone.
        // agentExecuted_Task_Loop_1 used to be asserted here; back then the twin never moved at
        // all, so it was still running to be read from.
        assertThat(twinAutomation(twin, "Task_Loop_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Loop_1")).isNotNull();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(agentExecuted(twin, "Task_Loop_1")).isNull();
    }

    // Real end-to-end regression for parallel multi-instance, through the actual bridge/
    // governance/twin-advance path rather than an isolated probe. Camunda's correlate() throws
    // the instant more than one execution matches a message name, which is exactly what three
    // parallel siblings waiting on the identical name produce - advanceTwinActivity resolves that
    // by loopCounter using messageEventReceived(name, executionId) to target one sibling at a
    // time, proven with a throwaway probe before this was written. This is what proves it holds
    // up against the real bridge, not just a hand-built model.
    @Test
    void parallelMultiInstanceActivityAdvancesEachSiblingIndependently() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel loop task test",
                parallelLoopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Gate", "Task_Gate");
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");

        // Task_Gate's start event fires during launchProcess, before the twin is tracked - same
        // registration-order gap the very first activity always has, sequential or not
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Gate").isApproved()).isTrue();

        // completing the gate opens all three parallel branches on the original at once, and the
        // twin's own three siblings get created the moment its Task_Gate automation ran above -
        // AutoBridgeTrigger fires once per branch, each individually resolved by loopCounter
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly("Task_Parallel", "Task_Parallel", "Task_Parallel");

        // all three twin siblings advanced independently - none collided, none left behind
        assertThat(twinAutomation(twin, "Task_Parallel_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_1")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_2")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_0")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_1")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_2")).isNotNull();
        // each branch really got its own agent rather than three writes landing on one variable
        assertThat(List.of(evolvedAgent(twin, "Task_Parallel_0"), evolvedAgent(twin, "Task_Parallel_1"),
                evolvedAgent(twin, "Task_Parallel_2"))).doesNotHaveDuplicates();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(4);
    }

    // Found by an adversarial review of the parallel-multi-instance work above. Before this pass,
    // the manual "Bridge selected activity" button against a parallel activity would throw on
    // every click, since advanceTwinActivity had no execution id to disambiguate with. The
    // two-argument convenience overload (bridge "whichever visit I'm sitting on") still can't tell
    // several simultaneously-open parallel siblings apart on its own - currentVisitId resolves to
    // the same not-yet-ended visit every time, so repeated clicks with no way to name a different
    // one keep landing on the first sibling and correctly report "already forwarded" rather than
    // making anything up or corrupting state.
    @Test
    void manualBridgeWithNoVisitSelectorKeepsResolvingTheSameParallelSibling() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel first activity test",
                parallelFirstBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");
        assertThat(openActivities(twin)).containsExactly("Task_Parallel", "Task_Parallel", "Task_Parallel");

        AgentDecision first = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel");
        assertThat(first.isApproved()).isTrue();
        int usedAfterFirst = governanceService.getUsage(twin.getId()).getTwinExecutionCount();
        assertThat(usedAfterFirst).isEqualTo(1);

        // repeated clicks resolve to the same already-forwarded visit - no crash, no slot leak,
        // but no further progress either
        AgentDecision second = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel");
        assertThat(second.isApproved()).isFalse();
        assertThat(second.getReason()).contains("already forwarded");
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(usedAfterFirst);
    }

    // What actually closes the gap the test above documents: bridgeActivityEvent's three-argument
    // overload (originally AutoBridgeTrigger's own entry point) takes the exact visit to bridge as
    // an activityInstanceId, and now advances the twin through that specific visit too - the same
    // consolidation that let AutoBridgeTrigger drop its separate advanceTwinActivity call entirely.
    // A caller who can name which of several open parallel siblings they mean - a future frontend
    // listing three distinct open tasks with three distinct ids, or this test reading them off
    // history the same way currentVisitId would - reaches all three individually, cleanly.
    @Test
    void bridgeActivityEventWithAnExplicitVisitReachesEveryParallelSibling() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel explicit visit test",
                parallelFirstBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");

        List<String> visitIds = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId("Task_Parallel")
                .unfinished()
                .list().stream().map(HistoricActivityInstance::getId).toList();
        assertThat(visitIds).hasSize(3);

        for (String visitId : visitIds) {
            AgentDecision decision = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel", visitId);
            assertThat(decision.isApproved()).isTrue();
        }

        assertThat(twinAutomation(twin, "Task_Parallel_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_1")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_2")).isNotNull();
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(3);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
    }

    @Test
    void rejectsRubbishInsteadOf500ing() throws IOException {
        assertThatThrownBy(() -> workbenchService.launchProcess(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.getTwinProcess(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.saveProcessModel(null, "not bpmn", "<nope/>"))
                .isInstanceOf(IllegalArgumentException.class);

        // we can't tell a model is unusable until it's already deployed, so the reject path has
        // to take the deployment back out again or cockpit slowly fills up with junk
        long deploymentsBefore = repositoryService.createDeploymentQuery().count();
        String notExecutable = citibankBpmn().replace("isExecutable=\"true\"", "isExecutable=\"false\"");
        assertThatThrownBy(() -> workbenchService.saveProcessModel(null, "not executable", notExecutable))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(deploymentsBefore);

        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer bad input",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThatThrownBy(() -> workbenchService.evolveActivity(twin.getId(), null, "validator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), "Task_NotInTheDiagram", KYC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void connect(TwinProcess twin, String... activityIds) {
        for (String activityId : activityIds) {
            workbenchService.connectActivity(twin.getId(), activityId, activityId);
        }
    }

    private List<String> openActivities(TwinProcess twin) {
        return taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list()
                .stream()
                .map(Task::getTaskDefinitionKey)
                .toList();
    }

    // via history like originalVariable below, and for the same reason: the twin now walks along
    // with the original and reaches its own end event first, so a runtimeService read on it throws
    private Object evolvedAgent(TwinProcess twin, String twinActivityId) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName("evolvedAgent_" + twinActivityId)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    // what the twin's own automation left behind on the activity it walked through
    private Object twinAutomation(TwinProcess twin, String twinActivityId) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName("twinAutomation_" + twinActivityId)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private boolean twinReached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    private Object agentExecuted(TwinProcess twin, String activityId) {
        return originalVariable(twin, "agentExecuted_" + activityId);
    }

    // via history, not runtimeService - the original has already ended by the time some of these
    // assertions run and reading a variable off a finished instance throws
    private Object originalVariable(TwinProcess twin, String variableName) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .variableName(variableName)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private boolean reached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    // walk up to find examples/ instead of copying the bpmn into test resources and letting them drift
    private static String citibankBpmn() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve("citibank-wire-transfer.bpmn");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/citibank-wire-transfer.bpmn above "
                + Path.of("").toAbsolutePath());
    }

    // small enough to just inline rather than another file in examples/ - only exists to give a
    // real sequential multi-instance activity for the bridge-tracking regression test above.
    // Carries the same complete listener as the two example models, or the test would only ever
    // exercise the bridge and never the delegate.
    private static String loopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_Loop" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LoopTask" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Loop" name="Loop Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Loop" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Loop" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // a plain (non-multi-instance) activity a token can revisit more than once - an ordinary
    // exclusive-gateway loop-back, not a loop characteristic - so there is no loopCounter anywhere
    // on either side to tell visit #1 and visit #2 apart by name
    private static String loopBackBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_LoopBack" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LoopBack" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Redo" name="Redo task">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:incoming>Flow_Loop</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:exclusiveGateway id="Gateway_Redo" default="Flow_End">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_Loop</bpmn:outgoing>
                      <bpmn:outgoing>Flow_End</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_End</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Redo" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Redo" targetRef="Gateway_Redo" />
                    <bpmn:sequenceFlow id="Flow_Loop" sourceRef="Gateway_Redo" targetRef="Task_Redo">
                      <bpmn:conditionExpression>${redo == true}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_End" sourceRef="Gateway_Redo" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // Task_Gate first so Task_Parallel's three branches open via the ordinary AFTER_COMMIT path
    // rather than the launch-time registration race every first activity has - the parallel
    // regression test above is about disambiguating three simultaneous siblings, not about that
    // separate, already-covered gap.
    private static String parallelLoopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_ParallelLoop" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ParallelLoopTask" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Gate" name="Gate Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_Parallel" name="Parallel Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Gate" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Gate" targetRef="Task_Parallel" />
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_Parallel" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // parallel MI with nothing ahead of it, so all three branches hit the same registration-order
    // gap the very first activity always has, and only the manual bridge button can reach any of
    // them - exactly the shape needed to see what that button can and can't do against a parallel
    // activity none of whose siblings have completed yet.
    private static String parallelFirstBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_ParallelFirst" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ParallelFirst" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Parallel" name="Parallel Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Parallel" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Parallel" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
