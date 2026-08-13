package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.model.AgentVariables;

import java.time.Instant;
import java.util.Map;

// Fallback automation for twins with no project-specific implementation; timestamps as proof of execution.
@Service("default")
public class DefaultProjectAutomationService implements ProjectAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProjectAutomationService.class);

    static final String RAN_AT_OUTPUT = "ranAt";

    @Override
    public AutomationResult execute(DelegateExecution execution) {
        Instant ranAt = Instant.now();
        // getCurrentActivityId() is the automation task's id; variables are keyed off the receive task's id
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());

        Object agent = execution.getVariable(AgentVariables.evolvedAgent(activityId,
                execution.getVariable("loopCounter")));

        logger.info("Twin activity {} on instance {} ran the default automation at {} (agent: {})",
                activityId, execution.getProcessInstanceId(), ranAt, agent == null ? "none" : agent);

        return new AutomationResult("default automation ran at " + ranAt,
                Map.of(RAN_AT_OUTPUT, ranAt.toString()));
    }
}
