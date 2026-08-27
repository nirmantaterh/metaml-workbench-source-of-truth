package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.bpmn.AgentOutputDeclarations;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import java.util.Map;

// On task complete: copies agent and outputs from twin to original for connected, evolved tasks.
@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionDelegate.class);

    // Camunda's own, set on the execution inside a multi-instance body
    private static final String LOOP_COUNTER_VARIABLE = "loopCounter";
    // one flag for the whole instance, not per-activity — read by a gateway downstream
    private static final String RISK_FLAG_VARIABLE = "agentFlaggedRisk";

    private final WorkbenchService workbenchService;
    private final RuntimeService runtimeService;
    private final AgentOutputDeclarations outputDeclarations;

    public AgentExecutionDelegate(WorkbenchService workbenchService, RuntimeService runtimeService,
            AgentOutputDeclarations outputDeclarations) {
        this.workbenchService = workbenchService;
        this.runtimeService = runtimeService;
        this.outputDeclarations = outputDeclarations;
    }

    // no try/catch: any engine exception already marks the transaction rollback-only; catching hides the cause.
    @Override
    public void notify(DelegateTask delegateTask) {
        String businessKey = delegateTask.getExecution().getProcessBusinessKey();
        if (!BusinessKeys.isOriginalKey(businessKey)) {
            // Camunda Tasklist can complete the twin's own task by hand; not just belt-and-braces
            return;
        }
        String twinId = BusinessKeys.twinIdFromOriginalKey(businessKey);

        TwinProcess twin = workbenchService.findTwinProcess(twinId);
        if (twin == null) {
            return;
        }

        // twin can end while original is still running; reading a variable off a finished instance throws
        if (!isRunning(twin.getTwinProcessId())) {
            return;
        }

        String activityId = delegateTask.getTaskDefinitionKey();
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return;
        }

        // multi-instance: each visit evolves its own agent; index prevents all visits collapsing into one variable
        Object loopCounter = delegateTask.getExecution().getVariable(LOOP_COUNTER_VARIABLE);

        Object agent = runtimeService.getVariable(twin.getTwinProcessId(),
                AgentVariables.evolvedAgent(twinActivityId, loopCounter));
        if (agent == null) {
            logger.debug("Agent execution skipped for activity {}: no agent evolved on twin instance {}",
                    twinActivityId, twin.getTwinProcessId());
            return;
        }

        String variableName = AgentVariables.agentExecuted(activityId, loopCounter);
        delegateTask.setVariable(variableName, agent);
        workbenchService.recordAgentExecution(twinId, variableName, agent);
        logger.debug("Agent {} executed for activity {} on twin {}", agent, activityId, twinId);

        Object outputIndex = runtimeService.getVariable(twin.getTwinProcessId(),
                AgentVariables.evolvedAgentOutputIndex(twinActivityId, loopCounter));
        Map<String, String> declared = outputDeclarations.forActivity(
                delegateTask.getProcessDefinitionId(), activityId);
        Object riskFlag = null;
        // riskFlagged requires explicit opt-in via metaml:agentOutputs to prevent accidental write-back.
        boolean riskFlagDeclared = RISK_FLAG_VARIABLE.equals(declared.get(AgentVariables.RISK_FLAGGED_OUTPUT));
        for (String outputName : AgentVariables.outputNamesIn(outputIndex)) {
            Object value = runtimeService.getVariable(twin.getTwinProcessId(),
                    AgentVariables.evolvedAgentOutput(outputName, twinActivityId, loopCounter));
            // prefix is mandatory: node manager has no auth, so a free-named output could clobber process variables like transferAmount or identityVerified
            String outputVariable = AgentVariables.agentOutput(activityId, outputName);
            // whatever the agent said, false included - these names are nobody's default flow
            delegateTask.setVariable(outputVariable, value);
            workbenchService.recordAgentExecution(twinId, outputVariable, value);

            // skip agentFlaggedRisk here; the block below owns it to enforce write-on-true semantics
            String declaredVariable = declared.get(outputName);
            if (declaredVariable != null && !RISK_FLAG_VARIABLE.equals(declaredVariable)) {
                delegateTask.setVariable(declaredVariable, value);
                workbenchService.recordAgentExecution(twinId, declaredVariable, value);
            }

            if (riskFlagDeclared && AgentVariables.RISK_FLAGGED_OUTPUT.equals(outputName)) {
                riskFlag = value;
            }
        }

        // bare name kept for Gateway_ChecksPassed in the citi model; write-on-true/remove-otherwise because the gateway takes its default flow when the variable is absent.
        if (Boolean.TRUE.equals(riskFlag)) {
            delegateTask.setVariable(RISK_FLAG_VARIABLE, true);
            workbenchService.recordAgentExecution(twinId, RISK_FLAG_VARIABLE, true);
            logger.debug("Agent {} flagged risk on activity {} of twin {}", agent, activityId, twinId);
        } else if (riskFlag != null) {
            delegateTask.removeVariable(RISK_FLAG_VARIABLE);
        }
    }

    private boolean isRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() != null;
    }
}
