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

/**
 * "complete" task listener (camunda:taskListener delegateExpression="${agentExecutionDelegate}")
 * for user tasks. When the original finishes a task, go find whatever agent the twin evolved for
 * that same activity and record it against the original, so evolvedAgent_* stops being a variable
 * nobody ever reads.
 *
 * <p>Gates on the ORIGINAL side, not the twin. completeCurrentTasks only moves the original and
 * nothing in here ever completes the twin's own copy of the task, so gating the other way round
 * would mean this never fires at all. The variable lives on the twin though, hence going through
 * WorkbenchService rather than reading this execution's own variables.
 *
 * <p>Goes through the twin's activityLinks before building the variable name - runEvolution
 * writes evolvedAgent_&lt;twinActivityId&gt;, and connectActivity lets that differ from the
 * original's activity id. Reading evolvedAgent_&lt;originalActivityId&gt; blindly missed that
 * case entirely.
 *
 * <p>Nothing runnable to call yet - the node manager hands back a name, not a component.
 * agentExecuted_* is the honest version of "it ran".
 */
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

    // TaskListener exceptions aren't swallowed by Camunda the way AutoBridgeTrigger's own
    // listener swallows its own - they roll back the task completion. This is meant to be a
    // side effect, not something that can fail someone's "Complete current task(s)" click.
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
            // the twin's own copy of this task is never completed by anything today, so this
            // branch would never fire in practice - see the class doc.
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

        // nothing to actually invoke yet, so just write down that it would have run here.
        // see the class doc.
        delegateTask.setVariable(AGENT_EXECUTED_VARIABLE_PREFIX + activityId, agent);
        logger.info("Agent {} executed for activity {} on business key {}", agent, activityId, businessKey);
    }
}
