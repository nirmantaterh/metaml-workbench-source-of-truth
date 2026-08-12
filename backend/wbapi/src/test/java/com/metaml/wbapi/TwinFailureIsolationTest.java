package com.metaml.wbapi;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// The one that matters most, given the history. An earlier attempt at this put the twin's job
// execution inside AgentExecutionDelegate, the complete listener on the original's task. It runs
// in the human's own transaction, so a twin that blew up marked that transaction rollback-only and
// the person clicking Complete got an error and no progress - and wrapping it in a try/catch
// changed nothing, because the transaction is already doomed by the time a catch block sees it.
//
// Doing it from AutoBridgeTrigger's after-commit worker instead is supposed to make that
// impossible. This is the test that says so: the default automation throws on every call, and the
// original still walks. The automation runs on the twin's automation service task now rather than
// in a job, so the throw comes back out of correlate() instead of out of executeJob(), but the
// thing being protected is the same.
//
// Own context on purpose - overriding the "default" automation bean is the only way to make it
// fail, and the other walkthroughs want the real one.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-isolation-test;DB_CLOSE_DELAY=-1"
})
class TwinFailureIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(TwinFailureIsolationTest.class);

    private static final String KYC = "Task_KYC";
    private static final String AML = "Task_AML";
    private static final String OFAC = "Task_OFAC";
    private static final String CREDIT = "Task_Credit";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    // replaces DefaultProjectAutomationService, which is registered under exactly this bean name
    @MockitoBean(name = "default")
    private ProjectAutomationService defaultAutomation;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ManagementService managementService;

    @BeforeEach
    void stubACatalogAndAnExplodingAutomation() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
        given(defaultAutomation.execute(any()))
                .willThrow(new ProcessEngineException("twin agent exploded"));
        governanceService.updatePolicy(Set.of(), 20, 200);
    }

    @Test
    void aTwinAutomationThatThrowsNeverReachesTheHumansTaskCompletion() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer exploding twin",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML, OFAC, CREDIT);

        // the manual bridge is the first thing to hit it. The evolution is committed by then, so
        // it keeps its result and the twin failure only shows up in the log.
        assertThatCode(() -> workbenchService.bridgeActivityEvent(twin.getId(), KYC))
                .doesNotThrowAnyException();
        assertThat(runtimeService.getVariable(twin.getTwinProcessId(), "evolvedAgent_" + KYC))
                .isEqualTo("validator-agent-01");
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("failed to execute"));

        // the twin never moved, so it is still sitting in front of its own KYC
        assertThat(twinToken(twin)).containsExactly(KYC);

        // and this is the whole point: the human's task completes, on a twin that is broken in the
        // exact way that used to take the completion down with it
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openTasks(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult()).isNotNull();

        // the trigger tried each of the three as well and each one failed the same way, without
        // any of it reaching the original
        assertThat(twinToken(twin)).containsExactly(KYC);
    }

    // Retry semantics, pinned, because they decide whether a twin that fails once is stuck for
    // good or quietly retried behind everyone's back. There is no retry and no retry counter: a
    // twin activity is a receive task, correlating its message runs the automation inside that one
    // command, and a throwing automation rolls the command back and rethrows. Nothing is scheduled
    // and no job row is ever written, which is also why the job executor being on changes nothing
    // here. The asyncBefore version of this test was watching a job's retries go 3 -> 3.
    @Test
    void aFailedTwinAdvanceLeavesNoJobBehindAndIsNeverRetried() throws IOException, InterruptedException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer twin retries",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        assertThat(twinJobs(twin)).isEmpty();

        workbenchService.bridgeActivityEvent(twin.getId(), KYC);

        // the failure did not turn into anything the engine will come back to
        assertThat(twinJobs(twin)).isEmpty();
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("twin agent exploded"));
        assertThat(twinToken(twin)).containsExactly(KYC);

        // and with the job executor running as Camunda ships it, nothing picks the twin back up
        Thread.sleep(3000);
        logger.info("Twin still at {} with {} job(s) three seconds after a failed advance",
                twinToken(twin), twinJobs(twin).size());
        assertThat(twinJobs(twin)).isEmpty();
        assertThat(twinToken(twin)).containsExactly(KYC);

        // asking again fails again, exactly the same way, rather than being refused as spent
        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), KYC))
                .hasMessageContaining("twin agent exploded");
    }

    private void connect(TwinProcess twin, String... activityIds) {
        for (String activityId : activityIds) {
            workbenchService.connectActivity(twin.getId(), activityId, activityId);
        }
    }

    private List<Job> twinJobs(TwinProcess twin) {
        return managementService.createJobQuery()
                .processInstanceId(twin.getTwinProcessId())
                .list();
    }

    // the twin's activity is a receive task, so the token is inside it and shows up as an
    // activity instance
    private List<String> twinToken(TwinProcess twin) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getTwinProcessId());
        if (tree == null) {
            return List.of();
        }
        return Arrays.stream(tree.getChildActivityInstances())
                .map(ActivityInstance::getActivityId)
                .sorted()
                .toList();
    }

    private List<String> openTasks(TwinProcess twin) {
        return taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list()
                .stream()
                .map(Task::getTaskDefinitionKey)
                .sorted()
                .toList();
    }

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
}
