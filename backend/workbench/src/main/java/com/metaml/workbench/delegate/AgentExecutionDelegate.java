package com.metaml.workbench.delegate;

import java.util.NoSuchElementException;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

// referenced from BPMN via camunda:taskListener delegateExpression="${agentExecutionDelegate}"
// on the "complete" event. Records the twin's evolved agent back onto the original.
@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionDelegate.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    private static final String EVOLVED_AGENT_VARIABLE_PREFIX = "evolvedAgent_";
    private static final String AGENT_EXECUTED_VARIABLE_PREFIX = "agentExecuted_";

    private final WorkbenchService workbenchService;
    private final RuntimeService runtimeService;

    public AgentExecutionDelegate(WorkbenchService workbenchService, RuntimeService runtimeService) {
        this.workbenchService = workbenchService;
        this.runtimeService = runtimeService;
    }

    // unlike AutoBridgeTrigger, Camunda won't swallow a TaskListener exception on its own -
    // it rolls back the task completion, which this is not supposed to be able to do
    @Override
    public void notify(DelegateTask delegateTask) {
        try {
            run(delegateTask);
        } catch (RuntimeException e) {
            logger.warn("Agent execution listener swallowed an error so the task completion survives: {}",
                    e.toString());
        }
    }

    private void run(DelegateTask delegateTask) {
        String businessKey = delegateTask.getExecution().getProcessBusinessKey();
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // twin's own copy of the task never completes, so this never fires for it anyway
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());

        TwinProcess twin;
        try {
            twin = workbenchService.getTwinProcess(twinId);
        } catch (NoSuchElementException e) {
            // twin already gone from the workbench's own bookkeeping, nothing to look up
            return;
        }

        String activityId = delegateTask.getTaskDefinitionKey();
        String twinActivityId = twin.resolveTwinActivityId(activityId);

        Object agent;
        try {
            agent = runtimeService.getVariable(twin.getTwinProcessId(),
                    EVOLVED_AGENT_VARIABLE_PREFIX + twinActivityId);
        } catch (ProcessEngineException e) {
            logger.debug("Could not read evolved agent for activity {} from twin instance {}: {}",
                    twinActivityId, twin.getTwinProcessId(), e.toString());
            return;
        }
        if (agent == null) {
            logger.debug("Agent execution skipped for activity {}: no agent evolved on twin instance {}",
                    twinActivityId, twin.getTwinProcessId());
            return;
        }

        // nothing real to call yet, so this just records that it would have run
        delegateTask.setVariable(AGENT_EXECUTED_VARIABLE_PREFIX + activityId, agent);
        logger.info("Agent {} executed for activity {} on business key {}", agent, activityId, businessKey);
    }
}
