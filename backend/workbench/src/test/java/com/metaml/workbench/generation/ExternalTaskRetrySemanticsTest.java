package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Verifies, against a REAL Camunda engine (not a mock, not generated source text), the exact retry
// semantics the generated ExternalTaskPoller.handleWorkerFailure relies on (see
// SpringBootProjectGenerator.writeExternalTaskPoller): a task's remaining retries start at
// maxRetries on first failure, decrement on each subsequent one, self-heal via the poller's own
// fetchAndLock cadence once the backoff window elapses (no job executor involved), and land in a
// real, queryable terminal state (retries == 0) rather than a silent stall once exhausted.
//
// This exercises the underlying Camunda mechanism directly rather than the generated Java text,
// which the workbench module has no compile-time dependency on (each generated project is meant to
// stand alone - see SpringBootProjectGenerator's own header). RedCollarEndToEndTest and
// GenericPlatformMechanismsEndToEndTest separately prove the generated poller's happy path executes
// for real inside a launched Target Platform; this test proves the failure/retry path specifically,
// with real task state asserted at each step - not merely that something was logged.
class ExternalTaskRetrySemanticsTest {

    private static final String WORKER_ID = "generated-worker";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 300L;

    private ProcessEngine engine;
    private String processInstanceId;

    @BeforeEach
    void setUp() {
        String dbName = "retry-semantics-" + UUID.randomUUID();
        ProcessEngineConfiguration configuration = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        configuration.setJdbcDriver("org.h2.Driver");
        configuration.setJdbcUsername("sa");
        configuration.setJdbcPassword("");
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        configuration.setJobExecutorActivate(false);
        engine = configuration.buildProcessEngine();

        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_RetryTest" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="RetryTestProcess" name="Retry Test Process" isExecutable="true"
                      camunda:historyTimeToLive="180">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="RetryStep" name="Retry Step" camunda:type="external"
                        camunda:topic="RetryTestTopic" />
                    <bpmn2:endEvent id="End" />
                    <bpmn2:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="RetryStep" />
                    <bpmn2:sequenceFlow id="Flow_2" sourceRef="RetryStep" targetRef="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        engine.getRepositoryService().createDeployment()
                .addModelInstance("retry-test.bpmn",
                        Bpmn.readModelFromStream(new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8))))
                .deploy();

        RuntimeService runtimeService = engine.getRuntimeService();
        processInstanceId = runtimeService.startProcessInstanceByKey("RetryTestProcess").getId();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void successfulExecutionCompletesTheTaskAndAdvancesTheProcess() {
        ExternalTaskService externalTaskService = engine.getExternalTaskService();
        LockedExternalTask task = fetchOne(externalTaskService);

        externalTaskService.complete(task.getId(), WORKER_ID);

        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult())
                .as("process instance should have completed and left the runtime")
                .isNull();
    }

    @Test
    void firstFailureSetsRemainingRetriesToMaxRetriesMinusOne() {
        ExternalTaskService externalTaskService = engine.getExternalTaskService();
        LockedExternalTask task = fetchOne(externalTaskService);
        assertThat(task.getRetries()).as("a never-failed task has no retries value yet").isNull();

        handleFailure(externalTaskService, task, "boom");

        ExternalTask afterFirstFailure = externalTaskService.createExternalTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(afterFirstFailure.getRetries()).isEqualTo(MAX_RETRIES - 1);
        assertThat(afterFirstFailure.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void taskSelfHealsAndIsRefetchableOncePositiveRetriesAndBackoffElapse() throws InterruptedException {
        ExternalTaskService externalTaskService = engine.getExternalTaskService();
        LockedExternalTask task = fetchOne(externalTaskService);

        handleFailure(externalTaskService, task, "transient failure");

        // Immediately after failure, the task is still within its retry backoff window - the
        // poller's own next tick must NOT be able to re-fetch it yet, or a fast-looping poller
        // would busy-retry with no backoff at all.
        assertThat(fetchAvailable(externalTaskService))
                .as("task must not be immediately refetchable while its retry backoff is still active")
                .isEmpty();

        // Once the backoff elapses, the poller's own normal polling cadence (not a job-executor
        // timer) is what picks the task back up - this is the "no giant retry subsystem" design:
        // the poller's existing fetchAndLock loop IS the retry mechanism.
        Thread.sleep(RETRY_BACKOFF_MS + 200);
        List<LockedExternalTask> refetched = fetchAvailable(externalTaskService);
        assertThat(refetched).as("task should self-heal and be refetchable once its backoff window elapses")
                .hasSize(1);
        assertThat(refetched.get(0).getId()).isEqualTo(task.getId());
    }

    @Test
    void exhaustingAllRetriesLeavesARealZeroRetryIncidentStateNotASilentStall() throws InterruptedException {
        ExternalTaskService externalTaskService = engine.getExternalTaskService();

        // Fail the task MAX_RETRIES times, mirroring exactly what the generated poller's
        // handleWorkerFailure does on each repeated exception: read current remaining retries,
        // decrement, hand back to Camunda.
        LockedExternalTask task = fetchOne(externalTaskService);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            handleFailure(externalTaskService, task, "failure #" + attempt);
            Thread.sleep(RETRY_BACKOFF_MS + 200);
            if (attempt < MAX_RETRIES) {
                List<LockedExternalTask> refetched = fetchAvailable(externalTaskService);
                assertThat(refetched).as("should still be retryable before the last attempt").hasSize(1);
                task = refetched.get(0);
            }
        }

        // Terminal state: zero retries remaining is real, queryable Camunda state (an incident-
        // eligible task), not merely something a log line claimed happened.
        ExternalTask exhausted = externalTaskService.createExternalTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(exhausted.getRetries()).as("task must show zero remaining retries after exhausting them all")
                .isEqualTo(0);
        assertThat(exhausted.getErrorMessage()).isEqualTo("failure #" + MAX_RETRIES);

        // And it must no longer be fetchable at all - the terminal state is enforced by the engine
        // itself (fetchAndLock excludes retries == 0), not by the poller remembering not to ask.
        assertThat(fetchAvailable(externalTaskService))
                .as("a task with zero retries must never be handed out by fetchAndLock again")
                .isEmpty();

        // The process instance is genuinely stuck at this activity, not silently vanished or
        // falsely reported complete - exactly the "process integrity" the audit asked to verify.
        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult())
                .as("process instance must still exist, parked at the failed activity")
                .isNotNull();
    }

    // Mirrors SpringBootProjectGenerator's generated ExternalTaskPoller.handleWorkerFailure exactly:
    // remaining = (currentRetries == null ? maxRetries : currentRetries) - 1, floored at zero.
    private static void handleFailure(ExternalTaskService externalTaskService, LockedExternalTask task,
            String errorMessage) {
        Integer currentRetries = task.getRetries();
        int remaining = (currentRetries == null ? MAX_RETRIES : currentRetries) - 1;
        externalTaskService.handleFailure(task.getId(), WORKER_ID, errorMessage, Math.max(remaining, 0),
                RETRY_BACKOFF_MS);
    }

    private static LockedExternalTask fetchOne(ExternalTaskService externalTaskService) {
        List<LockedExternalTask> fetched = fetchAvailable(externalTaskService);
        assertThat(fetched).hasSize(1);
        return fetched.get(0);
    }

    private static List<LockedExternalTask> fetchAvailable(ExternalTaskService externalTaskService) {
        return externalTaskService.fetchAndLock(10, WORKER_ID)
                .topic("RetryTestTopic", 10_000L)
                .execute();
    }
}
