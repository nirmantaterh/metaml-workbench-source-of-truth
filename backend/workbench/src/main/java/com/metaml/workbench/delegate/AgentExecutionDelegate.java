package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

// Referenced from BPMN via camunda:taskListener delegateExpression="${agentExecutionDelegate}"
// on the "complete" event, so it only fires for models that ask for it. When the original
// finishes a task that was connected and evolved, it copies the twin's chosen agent back onto
// the original as agentExecuted_* and puts a line in the twin's event log. Activities that were
// never connected, or connected but never evolved, are left alone.
@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionDelegate.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    // Camunda's own, set on the execution inside a multi-instance body
    private static final String LOOP_COUNTER_VARIABLE = "loopCounter";

    private final WorkbenchService workbenchService;
    private final RuntimeService runtimeService;

    public AgentExecutionDelegate(WorkbenchService workbenchService, RuntimeService runtimeService) {
        this.workbenchService = workbenchService;
        this.runtimeService = runtimeService;
    }

    // no try/catch here on purpose. There used to be one claiming it stopped a listener error
    // rolling back the task completion, and it never did: the transaction is already marked
    // rollback-only by the time a catch block sees an engine exception, so all it bought was
    // losing the real cause. The twin-instance check below is the thing that actually helps.
    @Override
    public void notify(DelegateTask delegateTask) {
        String businessKey = delegateTask.getExecution().getProcessBusinessKey();
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // completeCurrentTasks only ever touches the original, but Camunda Tasklist ships
            // with this app and will happily complete the twin's copy of a task by hand, so this
            // is a real check and not just belt and braces
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());

        TwinProcess twin = workbenchService.findTwinProcess(twinId);
        if (twin == null) {
            // gone from the workbench's own bookkeeping, nothing to look up
            return;
        }

        // the twin can end while the original is still going (computeStatus has a whole state for
        // it) and reading a variable off an instance that isn't there throws, so check first
        if (!isRunning(twin.getTwinProcessId())) {
            return;
        }

        String activityId = delegateTask.getTaskDefinitionKey();
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            // never connected, so there is no twin activity whose agent this could be
            return;
        }

        // a multi-instance task completes once per visit and each visit evolved its own agent,
        // so the loop index has to be part of both names or they all collapse into one
        Object loopCounter = delegateTask.getExecution().getVariable(LOOP_COUNTER_VARIABLE);

        Object agent = runtimeService.getVariable(twin.getTwinProcessId(),
                AgentVariables.evolvedAgent(twinActivityId, loopCounter));
        if (agent == null) {
            logger.debug("Agent execution skipped for activity {}: no agent evolved on twin instance {}",
                    twinActivityId, twin.getTwinProcessId());
            return;
        }

        // nothing real to call yet, so this just records that it would have run
        String variableName = AgentVariables.agentExecuted(activityId, loopCounter);
        delegateTask.setVariable(variableName, agent);
        workbenchService.recordAgentExecution(twinId, variableName, agent);
        logger.debug("Agent {} executed for activity {} on twin {}", agent, activityId, twinId);
    }

    private boolean isRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() != null;
    }
}
