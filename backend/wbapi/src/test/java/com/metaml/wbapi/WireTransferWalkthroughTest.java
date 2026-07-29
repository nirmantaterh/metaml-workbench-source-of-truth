package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        "workbench.state.persist=false"
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

        // the twin instance is still parked at its own KYC task - completeCurrentTasks only moves
        // the original, which is the known gap in the service, not a broken assertion
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus())
                .isEqualTo("TWIN_RUNNING_ORIGINAL_ENDED");

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
        assertThat(agentExecuted(twin, "Task_Loop_1")).isEqualTo("validator-agent-02");
        // the unsuffixed name is what the old one-per-activity write used
        assertThat(evolvedAgent(twin, "Task_Loop")).isNull();
        assertThat(agentExecuted(twin, "Task_Loop")).isNull();
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

    private Object evolvedAgent(TwinProcess twin, String twinActivityId) {
        return runtimeService.getVariable(twin.getTwinProcessId(), "evolvedAgent_" + twinActivityId);
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
}
