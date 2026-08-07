package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import java.util.Map;

// Runs as the twin's automation service task: camunda:delegateExpression="${twinAutomationDelegate}",
// synchronous, no async marker. It used to be an execution listener on the receive task itself, back
// when synchronization and automation were one element; splitting them meant this had to become a
// JavaDelegate on the new service task instead - the receive task now does nothing but wait, and this
// runs the instant that wait is over, in the same correlate() command, before the twin moves on.
// delegateExpression rather than class= to match agentExecutionDelegate next door - the engine
// resolves it against the Spring context, so this gets its dependencies injected like anything else.
//
// All it does is decide whose automation this twin belongs to, run it, and write down what came
// back. The deciding part is a map lookup: Spring collects every ProjectAutomationService bean by
// name, and a twin's projectId is the name it asks for.
@Component("twinAutomationDelegate")
public class TwinAutomationDelegate implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(TwinAutomationDelegate.class);

    private static final String DEFAULT_PROJECT_ID = "default";
    private static final String LOOP_COUNTER_VARIABLE = "loopCounter";

    private final Map<String, ProjectAutomationService> automationsByProject;
    private final WorkbenchService workbenchService;

    public TwinAutomationDelegate(Map<String, ProjectAutomationService> automationsByProject,
            WorkbenchService workbenchService) {
        this.automationsByProject = automationsByProject;
        this.workbenchService = workbenchService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        // execution.getCurrentActivityId() here is this service task's own id (the automation task,
        // e.g. Task_KYC_automate), not the receive task's - every twinAutomation_<id>/
        // evolvedAgent_<id> variable is named after the synchronization point instead, so recover it
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());
        ProjectAutomationService automation = automationFor(execution);
        AutomationResult result = automation.execute(execution);

        Object loopCounter = execution.getVariable(LOOP_COUNTER_VARIABLE);
        execution.setVariable(AgentVariables.twinAutomation(activityId, loopCounter), result.summary());
        for (Map.Entry<String, Object> output : result.outputs().entrySet()) {
            execution.setVariable(
                    AgentVariables.twinAutomationOutput(output.getKey(), activityId, loopCounter),
                    output.getValue());
        }
    }

    // The business key is the only thing on the execution that ties it back to a twin the workbench
    // knows about. A twin that has fallen out of the workbench's own bookkeeping still has a real
    // token to move, so it gets the default automation rather than an exception.
    private ProjectAutomationService automationFor(DelegateExecution execution) {
        String projectId = DEFAULT_PROJECT_ID;
        String businessKey = execution.getProcessBusinessKey();
        if (BusinessKeys.isTwinKey(businessKey)) {
            TwinProcess twin = workbenchService.findTwinProcess(
                    BusinessKeys.twinIdFromTwinKey(businessKey));
            if (twin != null && twin.getProjectId() != null && !twin.getProjectId().isBlank()) {
                projectId = twin.getProjectId();
            }
        }

        ProjectAutomationService automation = automationsByProject.get(projectId);
        if (automation != null) {
            return automation;
        }
        // a project id with no bean behind it is a configuration mistake, not a reason to fail the
        // twin's job and leave its token stuck where the original has already moved on from
        logger.warn("No ProjectAutomationService named '{}' for twin instance {}, falling back to '{}'. "
                + "Registered: {}", projectId, execution.getProcessInstanceId(), DEFAULT_PROJECT_ID,
                automationsByProject.keySet());
        return automationsByProject.get(DEFAULT_PROJECT_ID);
    }
}
