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

// Runs automation synchronously on the twin's service task, looked up by projectId bean name.
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
        // getCurrentActivityId() returns the automation task's id; recover the receive task's id that variables key off
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

    // falls back to default if the twin is no longer in bookkeeping but its token is still live
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
        // missing bean is a config mistake; don't leave the twin's token stuck
        logger.warn("No ProjectAutomationService named '{}' for twin instance {}, falling back to '{}'. "
                + "Registered: {}", projectId, execution.getProcessInstanceId(), DEFAULT_PROJECT_ID,
                automationsByProject.keySet());
        return automationsByProject.get(DEFAULT_PROJECT_ID);
    }
}
