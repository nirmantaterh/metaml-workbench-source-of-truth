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

// Referenced from BPMN via camunda:taskListener delegateExpression="${agentExecutionDelegate}"
// on the "complete" event, so it only fires for models that ask for it. When the original
// finishes a task that was connected and evolved, it copies the twin's chosen agent back onto
// the original as agentExecuted_*, copies whatever that agent reported across as agentOutput_*,
// and puts a line in the twin's event log for each. Activities that were never connected, or
// connected but never evolved, are left alone.
@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionDelegate.class);

    // Camunda's own, set on the execution inside a multi-instance body
    private static final String LOOP_COUNTER_VARIABLE = "loopCounter";
    // read by a gateway further down the model, so it is deliberately one flag for the whole
    // instance and not suffixed per activity like the two above
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

    // no try/catch here on purpose. There used to be one claiming it stopped a listener error
    // rolling back the task completion, and it never did: the transaction is already marked
    // rollback-only by the time a catch block sees an engine exception, so all it bought was
    // losing the real cause. The twin-instance check below is the thing that actually helps.
    @Override
    public void notify(DelegateTask delegateTask) {
        String businessKey = delegateTask.getExecution().getProcessBusinessKey();
        if (!BusinessKeys.isOriginalKey(businessKey)) {
            // completeCurrentTasks only ever touches the original, but Camunda Tasklist ships
            // with this app and will happily complete the twin's copy of a task by hand, so this
            // is a real check and not just belt and braces
            return;
        }
        String twinId = BusinessKeys.twinIdFromOriginalKey(businessKey);

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

        // no index means this visit's evolution reported nothing, same as an absent variable
        // anywhere else round here
        Object outputIndex = runtimeService.getVariable(twin.getTwinProcessId(),
                AgentVariables.evolvedAgentOutputIndex(twinActivityId, loopCounter));
        // whatever this activity's author asked to have republished under a name of their own.
        // Read once per completion rather than per output; most activities declare nothing.
        Map<String, String> declared = outputDeclarations.forActivity(
                delegateTask.getProcessDefinitionId(), activityId);
        Object riskFlag = null;
        // Phase 9/10 red team finding: this used to treat ANY output literally named "riskFlagged"
        // as routing-relevant, on every activity, in every project, whether or not that project's
        // BPMN ever opted into it - the one write-back channel from Twin to Original that isn't
        // gated the same way every other output already is (declaredVariable below). Now it
        // requires the SAME opt-in every other output needs: the activity's own metaml:agentOutputs
        // declaration has to name "riskFlagged" mapped to "agentFlaggedRisk" specifically. The
        // citibank model already declares exactly that for Task_Credit (asserted directly in
        // TwinExecutionWalkthroughTest), so this changes nothing for it - it only stops a future
        // project's automation from inheriting this channel by accident, purely because one of its
        // output names happens to collide with this one's.
        boolean riskFlagDeclared = RISK_FLAG_VARIABLE.equals(declared.get(AgentVariables.RISK_FLAGGED_OUTPUT));
        for (String outputName : AgentVariables.outputNamesIn(outputIndex)) {
            Object value = runtimeService.getVariable(twin.getTwinProcessId(),
                    AgentVariables.evolvedAgentOutput(outputName, twinActivityId, loopCounter));
            // the prefix isn't decoration and isn't up for configuring: the node manager has no
            // authentication in front of it, and an output free to pick its own name would let
            // one bad catalog answer land on transferAmount or identityVerified instead
            String outputVariable = AgentVariables.agentOutput(activityId, outputName);
            // whatever the agent said, false included - these names are nobody's default flow
            delegateTask.setVariable(outputVariable, value);
            workbenchService.recordAgentExecution(twinId, outputVariable, value);

            // The prefixed name above always happens; this is the extra copy under the short name
            // the BPMN asked for. Safe in a way letting the catalog choose wouldn't be, because
            // the person picking it is the one who wrote the model the variable lands in.
            // Skip it if that short name happens to be agentFlaggedRisk itself - the block below
            // already owns that one and has its own true/absent rule, so this would just write the
            // same variable twice and log the same line twice.
            String declaredVariable = declared.get(outputName);
            if (declaredVariable != null && !RISK_FLAG_VARIABLE.equals(declaredVariable)) {
                delegateTask.setVariable(declaredVariable, value);
                workbenchService.recordAgentExecution(twinId, declaredVariable, value);
            }

            if (riskFlagDeclared && AgentVariables.RISK_FLAGGED_OUTPUT.equals(outputName)) {
                riskFlag = value;
            }
        }

        // riskFlagged also keeps its old bare name, permanently. Gateway_ChecksPassed in the citi
        // model reads that name and takes its default flow when the variable is absent, so this
        // one stays write-on-true / remove-otherwise rather than writing an explicit false.
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
