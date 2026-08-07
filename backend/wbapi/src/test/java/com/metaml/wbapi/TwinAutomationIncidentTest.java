package com.metaml.wbapi;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.runtime.Incident;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Regression coverage for the incident-handling fix: a twin activity's automation throwing must
// record a real, resolvable Camunda Incident instead of only a log line, leave the original
// completely untouched, leave the twin exactly where it was (safe to retry), and never invoke or
// depend on the job executor anywhere in the process. Deliberately its own SpringBootTest context
// (own datasource, own ProjectAutomationService mock) rather than reusing an existing walkthrough
// class, so overriding the "default" automation bean here can't affect any other test's automation
// behavior.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-twin-automation-incident-test;DB_CLOSE_DELAY=-1",
        "workbench.state.persist=false",
        "workbench.models.directory=./target/test-data/models"
})
class TwinAutomationIncidentTest {

    private static final String TASK_A = "Task_A";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;
    @MockitoBean(name = "default")
    private ProjectAutomationService defaultAutomation;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ManagementService managementService;

    @Test
    void automationFailureRecordsAnIncidentLeavesTheOriginalUntouchedAndSupportsRetry() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
        given(defaultAutomation.execute(any(DelegateExecution.class)))
                .willThrow(new IllegalStateException("automation exploded"))
                .willReturn(AutomationResult.of("recovered"));

        ProcessModel model = workbenchService.saveProcessModel(null, "incident test", singleTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_A, TASK_A);

        // (5) no job executor dependency anywhere - checked before, during, and after the failure
        assertThat(managementService.createJobQuery().processInstanceId(twin.getTwinProcessId()).count()).isZero();

        // Task_A's start event fires during launchProcess, before the twin is tracked - manual
        // bridge is the only way to reach it, same reason KYC gets bridged by hand elsewhere
        AgentDecision firstAttempt = workbenchService.bridgeActivityEvent(twin.getId(), TASK_A);
        assertThat(firstAttempt.isApproved()).isTrue(); // evolve/bridge itself is unaffected by automation failing

        // (1) a real, resolvable incident was recorded
        Incident incident = runtimeService.createIncidentQuery()
                .processInstanceId(twin.getTwinProcessId()).singleResult();
        assertThat(incident).isNotNull();
        assertThat(incident.getIncidentType()).isEqualTo("twinAutomationFailure");
        assertThat(incident.getIncidentMessage()).contains("automation exploded");

        // (2) the original is completely unaffected - its own task is still there, untouched, and
        // completing it works exactly as it would with no twin failure anywhere in the picture
        assertThat(taskService.createTaskQuery().processInstanceId(twin.getOriginalProcessId())
                .taskDefinitionKey(TASK_A).count()).isEqualTo(1);

        // (3) the twin did not advance past the failed service task - still exactly one receive
        // task waiting on the same message, same execution, nothing consumed
        assertThat(runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isEqualTo(1);
        assertThat(runtimeService.getActiveActivityIds(twin.getTwinProcessId())).containsExactly(TASK_A);

        // (4) retry: re-bridging the same activity (the operator's "explicitly re-bridge" action)
        // succeeds without duplicating anything - automation ran exactly twice total (the failed
        // attempt, then the successful one), not zero and not more
        AgentDecision retry = workbenchService.bridgeActivityEvent(twin.getId(), TASK_A);
        assertThat(retry.getReason()).contains("already forwarded"); // evolve itself was never re-attempted
        org.mockito.Mockito.verify(defaultAutomation, org.mockito.Mockito.times(2)).execute(any(DelegateExecution.class));

        assertThat(runtimeService.createIncidentQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero(); // no longer stuck
        assertThat(runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero(); // moved on

        assertThat(managementService.createJobQuery().processInstanceId(twin.getTwinProcessId()).count()).isZero();

        // completing the original's own task still works normally throughout
        List<String> completed = workbenchService.completeCurrentTasks(twin.getId());
        assertThat(completed).hasSize(1);
    }

    private static String singleTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_IncidentProbe" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_IncidentProbe" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_A" name="Task A">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_A" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_A" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
