package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Attach as a "complete" task listener (camunda:taskListener delegateExpression=
 * "${agentExecutionDelegate}") on any user task in any process to have whatever agent the twin
 * evolved for that activity actually run when the real (original) process reaches the same step,
 * instead of evolvedAgent_* just sitting on the twin as an unread variable.
 *
 * <p>Fires on the ORIGINAL completing the task, not the twin - completeCurrentTasks only ever
 * advances the original side, the twin's own copy of the task is never completed by anything in
 * this codebase, so gating on the twin side would mean this never runs in practice. evolvedAgent_*
 * lives on the twin's process instance though, so this looks it up there by business key rather
 * than reading its own execution's variables.
 *
 * <p>There's no real agent behind evolvedAgent_* yet (the node manager only hands back a name),
 * so this can't call out to anything real. What it can honestly do is record that the original
 * would have run that agent right here, which is a real, inspectable, verifiable side effect
 * instead of a metadata tag nobody reads.
 */
@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionDelegate.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    private static final String TWIN_BUSINESS_KEY_PREFIX = "twin-";
    private static final String EVOLVED_AGENT_VARIABLE_PREFIX = "evolvedAgent_";
    private static final String AGENT_EXECUTED_VARIABLE_PREFIX = "agentExecuted_";

    private final RuntimeService runtimeService;

    public AgentExecutionDelegate(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        String businessKey = delegateTask.getExecution().getProcessBusinessKey();
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // the twin's own copy of this task is never completed by anything today, so this
            // branch would never fire in practice - see the class doc.
            return;
        }

        String twinBusinessKey = TWIN_BUSINESS_KEY_PREFIX
                + businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());
        ProcessInstance twinInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(twinBusinessKey)
                .singleResult();
        if (twinInstance == null) {
            // no twin ever launched with this business key, or it already ended
            return;
        }

        String activityId = delegateTask.getTaskDefinitionKey();
        Object agent;
        try {
            agent = runtimeService.getVariable(twinInstance.getId(), EVOLVED_AGENT_VARIABLE_PREFIX + activityId);
        } catch (ProcessEngineException e) {
            logger.debug("Could not read evolved agent for activity {} from twin instance {}: {}",
                    activityId, twinInstance.getId(), e.toString());
            return;
        }
        if (agent == null) {
            logger.debug("Agent execution skipped for activity {}: no agent evolved on twin instance {}",
                    activityId, twinInstance.getId());
            return;
        }

        // nothing real to call yet - the node manager only hands back a name, not a runnable
        // component. record that the original would have run it, so this is provable rather than
        // a variable nobody ever reads.
        delegateTask.setVariable(AGENT_EXECUTED_VARIABLE_PREFIX + activityId, agent);
        logger.info("Agent {} executed for activity {} on business key {}", agent, activityId, businessKey);
    }
}
