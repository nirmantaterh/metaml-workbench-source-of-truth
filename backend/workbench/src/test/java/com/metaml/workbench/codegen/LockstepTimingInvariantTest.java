package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.bpmn.TwinModelGenerator;

// Automated version of the manual delayed-Twin race test performed during the lockstep sync
// implementation/review: proves the generated BPMN's signal gates - combined with Camunda's
// synchronous, transactional delegate execution - genuinely block Proxy from entering Task N+1
// until Twin's Task N has COMPLETED, not merely started or been signaled. LockstepSyncIntegrationTest
// (in this same package) only asserts on the SHAPE of the generated BPMN; this test deploys that
// real output to a real engine and asserts the actual runtime ordering.
//
// Deliberately does not involve SignalBroadcaster, RabbitMQ, or a generated Spring Boot project:
// SignalBroadcaster only exists as a string template inside SpringBootProjectGenerator (see
// writeSignalBroadcaster), not as a class this module can import, so it cannot be unit-tested
// directly. What CAN be tested directly - and what actually establishes the invariant - is the
// primitive SignalBroadcaster is built on: runtimeService.signalEventReceived() is synchronous and
// transactional, and a later EventSubscription query only ever sees state from AFTER that
// transaction (delegate execution included) commits. This test drives that same primitive by hand,
// in the same two-phase REQUEST-then-RESPONSE-after-confirming-advancement shape SignalBroadcaster
// uses (see its own responderHasAdvancedPast comment), deterministically via a CountDownLatch
// instead of the broadcaster's 1-second poll cadence.
class LockstepTimingInvariantTest {

    private static final String PROXY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_timing" targetNamespace="http://metaml.com/bpmn">
              <bpmn:process id="LockstepTiming" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>Flow_1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="Flow_1" sourceRef="start" targetRef="taskA"/>
                <bpmn:serviceTask id="taskA" name="Task A" camunda:delegateExpression="${taskA}">
                  <bpmn:incoming>Flow_1</bpmn:incoming>
                  <bpmn:outgoing>Flow_2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_2" sourceRef="taskA" targetRef="taskB"/>
                <bpmn:serviceTask id="taskB" name="Task B" camunda:delegateExpression="${taskB}">
                  <bpmn:incoming>Flow_2</bpmn:incoming>
                  <bpmn:outgoing>Flow_3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_3" sourceRef="taskB" targetRef="end"/>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>Flow_3</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();
    private ProcessEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    // Stands in for the real generated "taskA_automate" delegate (which the running generator
    // would render as an instant System.out.println stub - see TargetPlatformSourceGenerator's own
    // render()). Blocks on a caller-supplied latch instead, so "twin's Task A is still running" is
    // a controllable state, not something only Thread.sleep can approximate.
    private static final class LatchedDelegate implements JavaDelegate {
        private final CountDownLatch releaseLatch;
        private final CountDownLatch startedSignal = new CountDownLatch(1);
        private volatile Instant completedAt;

        LatchedDelegate(CountDownLatch releaseLatch) {
            this.releaseLatch = releaseLatch;
        }

        @Override
        public void execute(DelegateExecution execution) throws Exception {
            startedSignal.countDown();
            if (!releaseLatch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release latch was never counted down");
            }
            completedAt = Instant.now();
        }
    }

    private static final class InstantDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            // no-op stand-in for the real generated stub
        }
    }

    @Test
    void proxyCannotEnterTaskBWhileTwinsTaskAIsStillBlocked() throws Exception {
        // 1. Real generator output for both sides - the exact same production code path
        // LockstepSyncIntegrationTest exercises, unmodified.
        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(PROXY_BPMN, false);
        BpmnModelInstance proxyModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(PROXY_BPMN.getBytes(StandardCharsets.UTF_8)));
        String twinBpmn = Bpmn.convertToString(new TwinModelGenerator().generate(proxyModel));
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(twinBpmn, true,
                proxyResult.syncActivityIds());

        // 2. Test-only delegate substitutes, registered directly under the bean names
        // TargetPlatformSourceGenerator normalized the transformed BPMNs' delegateExpressions to:
        // camel(activityId) for proxy tasks, camel(activityId + "_automate") + "Twin" for twin
        // tasks - the "Twin" suffix keeps a twin bean name distinct from a proxy bean name even
        // when both sides reference the same underlying activity id (see generate()'s own
        // normalisation and its ConflictingBeanDefinitionException-avoidance comment). No Spring
        // context, no component scanning: a standalone engine resolves "${beanName}" against this
        // map directly.
        CountDownLatch releaseLatch = new CountDownLatch(1);
        LatchedDelegate twinTaskADelegate = new LatchedDelegate(releaseLatch);
        Map<Object, Object> beans = Map.of(
                "taskA", new InstantDelegate(),
                "taskB", new InstantDelegate(),
                "taskA_automateTwin", twinTaskADelegate,
                "taskB_automateTwin", new InstantDelegate());

        ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl)
                ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
        String uniqueSuffix = UUID.randomUUID().toString();
        config.setProcessEngineName("lockstep-timing-" + uniqueSuffix);
        config.setJdbcUrl("jdbc:h2:mem:lockstep-timing-" + uniqueSuffix + ";DB_CLOSE_DELAY=-1");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(false);
        config.setBeans(beans);
        engine = config.buildProcessEngine();

        RuntimeService runtimeService = engine.getRuntimeService();
        HistoryService historyService = engine.getHistoryService();
        RepositoryService repositoryService = engine.getRepositoryService();

        // 3. Deploy the REAL transformed BPMNs (post lockstep-sync insertion), not the raw fixture.
        repositoryService.createDeployment()
                .addString("proxy.bpmn", proxyResult.bpmnXml())
                .addString("twin.bpmn", twinResult.bpmnXml())
                .deploy();

        ProcessInstance proxy = runtimeService.startProcessInstanceByKey("LockstepTiming");
        ProcessInstance twin = runtimeService.startProcessInstanceByKey("LockstepTiming_twin");

        // 4. Bounded wait (not a fixed sleep) until both sides are genuinely parked on sync_taskA -
        // otherwise "deliver the signal" below would have nothing to deliver to.
        awaitEventSubscription(runtimeService, proxy.getId(), "sync_taskA", Duration.ofSeconds(10));
        awaitEventSubscription(runtimeService, twin.getId(), "sync_taskA", Duration.ofSeconds(10));

        // 5. REQUEST: deliver sync_taskA to twin, exactly as SignalBroadcaster's REQUEST phase
        // would. This call is synchronous and will not return until twin's whole flow through
        // taskA_automate.execute() completes - right now, that means it blocks on releaseLatch.
        // Run it on its own thread so this test thread can keep asserting proxy's state while
        // twin is provably still inside that blocked call.
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        Thread requestDelivery = new Thread(() -> {
            try {
                String twinExecutionId = subscriptionExecutionId(runtimeService, twin.getId(), "sync_taskA");
                runtimeService.signalEventReceived("sync_taskA", twinExecutionId);
            } catch (Throwable t) {
                deliveryFailure.set(t);
            }
        }, "twin-request-delivery");
        requestDelivery.start();

        boolean delegateStarted = twinTaskADelegate.startedSignal.await(10, TimeUnit.SECONDS);
        assertThat(delegateStarted).as("twin's taskA_automate delegate should have started").isTrue();

        // 6. THE INVARIANT UNDER TEST, asserted while twin is provably still blocked inside
        // taskA_automate (releaseLatch has not been counted down yet): proxy must still be exactly
        // where it was before the REQUEST was sent - never having entered taskB.
        assertThat(runtimeService.getActiveActivityIds(proxy.getId()))
                .as("proxy must remain blocked at its own sync_taskA gate while twin's Task A is still running")
                .containsExactly("sync_evt_taskA");
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(proxy.getId()).activityId("taskB").count())
                .as("proxy must not have entered taskB while twin's Task A is still blocked")
                .isZero();

        // 7. Release twin's Task A, then wait for the REQUEST delivery call to actually return -
        // i.e. for twin's transaction, delegate execution included, to commit.
        releaseLatch.countDown();
        requestDelivery.join(Duration.ofSeconds(10).toMillis());
        assertThat(requestDelivery.isAlive()).as("REQUEST delivery thread should have returned by now").isFalse();
        assertThat(deliveryFailure.get()).as("REQUEST delivery must not have thrown").isNull();
        assertThat(twinTaskADelegate.completedAt).as("twin's taskA_automate must have completed").isNotNull();

        // 8. RESPONSE, gated on the same rule SignalBroadcaster's responderHasAdvancedPast uses: a
        // FRESH query (not reused state from before the release) must show twin is no longer
        // subscribed to sync_taskA before RESPONSE is sent - proof twin's transaction, delegate
        // included, has actually committed, not just that the earlier call returned in-process.
        boolean twinAdvancedPastTaskA = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getId()).eventType("signal").list().stream()
                .noneMatch(subscription -> "sync_taskA".equals(subscription.getEventName()));
        assertThat(twinAdvancedPastTaskA).as("twin must have moved off sync_taskA before RESPONSE is sent").isTrue();

        String proxyExecutionId = subscriptionExecutionId(runtimeService, proxy.getId(), "sync_taskA");
        runtimeService.signalEventReceived("sync_taskA", proxyExecutionId);

        // 9. Proxy Task N+1 (taskB) must now execute.
        awaitHistoricActivity(historyService, proxy.getId(), "taskB", Duration.ofSeconds(10));

        // 10. Direct ordering proof, from the engine's own recorded history timestamps - not test-
        // thread clocks, not log lines: twin's taskA_automate must have committed its end time
        // before proxy's taskB started.
        HistoricActivityInstance twinAutomate = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getId()).activityId("taskA_automate").singleResult();
        HistoricActivityInstance proxyTaskB = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(proxy.getId()).activityId("taskB").singleResult();
        assertThat(twinAutomate).as("twin's taskA_automate must have a recorded history entry").isNotNull();
        assertThat(proxyTaskB).as("proxy's taskB must have a recorded history entry").isNotNull();
        assertThat(twinAutomate.getEndTime())
                .as("twin's taskA_automate must have committed its end time before proxy's taskB started")
                .isBeforeOrEqualTo(proxyTaskB.getStartTime());
    }

    private static void awaitEventSubscription(RuntimeService runtimeService, String processInstanceId,
            String signalName, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            boolean waiting = !runtimeService.createEventSubscriptionQuery()
                    .processInstanceId(processInstanceId).eventType("signal").eventName(signalName)
                    .list().isEmpty();
            if (waiting) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process instance " + processInstanceId + " never subscribed to " + signalName);
    }

    private static void awaitHistoricActivity(HistoryService historyService, String processInstanceId,
            String activityId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            long count = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId).activityId(activityId).count();
            if (count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process instance " + processInstanceId + " never entered " + activityId);
    }

    private static String subscriptionExecutionId(RuntimeService runtimeService, String processInstanceId,
            String signalName) {
        List<EventSubscription> subscriptions = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(processInstanceId).eventType("signal").eventName(signalName).list();
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException(
                    "no event subscription for '" + signalName + "' on process instance " + processInstanceId);
        }
        return subscriptions.get(0).getExecutionId();
    }
}
