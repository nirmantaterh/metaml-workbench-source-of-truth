package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.model.AgentVariables;

import java.time.Instant;
import java.util.Map;

// The automation every twin gets unless its project says otherwise. There is exactly one of these
// on purpose: the registry only needs one real implementation to be a working extension point, and
// making up business rules for the other course projects would be inventing requirements nobody
// gave us.
//
// It still has to do something you can point at, or a twin activity "running" would be
// indistinguishable from the job never having been executed. So it timestamps itself, both into
// the log and onto the twin instance.
@Service("default")
public class DefaultProjectAutomationService implements ProjectAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProjectAutomationService.class);

    static final String RAN_AT_OUTPUT = "ranAt";

    @Override
    public AutomationResult execute(DelegateExecution execution) {
        Instant ranAt = Instant.now();
        // execution.getCurrentActivityId() here is the automation service task's own id
        // (e.g. Task_KYC_automate), not the synchronization point's - evolvedAgent_<id> and every
        // other twin-side variable are named after the receive task, so recover that id first or
        // this always misses and logs "agent: none" even when one really was evolved
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());

        // whatever the bridge evolved for this activity a moment ago, so the log line ties the
        // twin's own step back to the agent that was picked for it
        Object agent = execution.getVariable(AgentVariables.evolvedAgent(activityId,
                execution.getVariable("loopCounter")));

        logger.info("Twin activity {} on instance {} ran the default automation at {} (agent: {})",
                activityId, execution.getProcessInstanceId(), ranAt, agent == null ? "none" : agent);

        return new AutomationResult("default automation ran at " + ranAt,
                Map.of(RAN_AT_OUTPUT, ranAt.toString()));
    }
}
