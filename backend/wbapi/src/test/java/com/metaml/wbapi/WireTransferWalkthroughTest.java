package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Runs the wire transfer walkthrough against the real embedded engine instead of by hand:
 * save + deploy the Citi wire transfer model, launch the twin, connect activities, let the
 * auto-bridge do its thing, and drive the original all the way to EndEvent_Success.
 *
 * <p>The node manager is the one thing stubbed. It's a static catalog behind an HTTP call on a
 * fixed port, and a test that needs a second Spring Boot app already listening on 8083 is a test
 * nobody runs. Everything else here is the real thing - real deployment, real user tasks, real
 * parallel gateway, real transaction-synchronised bridge.
 */
// mem, not the file db the app itself now uses - see WbapiApplicationTests. same url string on
// purpose so both classes share one context instead of booting the engine twice.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1",
        "workbench.state.persist=false"
})
class WireTransferWalkthroughTest {

    private static final String KYC = "Task_KYC";
    private static final String AML = "Task_AML";
    private static final String OFAC = "Task_OFAC";
    private static final String CREDIT = "Task_Credit";
    private static final String APPROVE = "Task_Approve";
    private static final String EXECUTE = "Task_Execute";
    private static final String NOTIFY = "Task_Notify";

    // what the bridge picks when no caller supplied a type, and what the real catalog answers
    private static final String BRIDGE_AGENT = "validator-agent-01";

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
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog");
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

    // manual evolve and the auto-bridge landing on the same activity at the same moment. the
    // stubbed catalog parks whoever gets in first, so the second one is guaranteed to arrive while
    // the first is still mid-evolution rather than us hoping the threads happen to overlap.
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
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog");
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

    // regression test for the flat-set bug: forwardedBridgeActivities used to key on activityId
    // alone, so a sequential multi-instance task's second visit looked like a duplicate of the
    // first and got silently skipped instead of bridged.
    @Test
    void multiInstanceActivityBridgesEveryVisitNotJustTheFirst() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "loop task test", loopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Loop", "Task_Loop");

        // visit #1's start event fires during launchProcess, before the twin is tracked - same
        // reason KYC gets bridged by hand elsewhere in this file
        AgentDecision firstVisit = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Loop");
        assertThat(firstVisit.isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // completing visit #1 immediately opens visit #2 of the same multi-instance activity -
        // same activityId, a different activityInstanceId under the hood. auto-bridge should pick
        // this one up on its own rather than treating it as already forwarded.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);
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

    private boolean reached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    // surefire runs from backend/wbapi, an IDE might run from somewhere else. walk up until the
    // examples folder turns up rather than copying the bpmn into test resources and letting the
    // two drift.
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
    // real sequential multi-instance activity for the bridge-tracking regression test above
    private static String loopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_Loop" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LoopTask" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Loop" name="Loop Task">
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
