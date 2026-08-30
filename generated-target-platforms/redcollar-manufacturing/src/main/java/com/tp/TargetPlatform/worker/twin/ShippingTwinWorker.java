package com.tp.TargetPlatform.worker.twin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "ShippingTwin" (BPMN activity "Shipping Twin").
@Component
public class ShippingTwinWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(ShippingTwinWorker.class);

    private final ObjectProvider<TwinDecisionAgent> agentProvider;

    public ShippingTwinWorker(ObjectProvider<TwinDecisionAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    @Override
    public String topic() {
        return "ShippingTwin";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        TwinDecisionAgent agent = agentProvider.getIfAvailable();
        Map<String, Object> variables;
        if (agent != null) {
            logger.info("[Twin] Invoking decision agent {} for activity \"Shipping Twin\" "
                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
            variables = new HashMap<>(agent.decide("ShippingTwin", task));
        } else {
            // No TwinDecisionAgent registered - built-in fallback. Produces synthetic, runtime-varying output rather than a predetermined business outcome so the twin can still run standalone; register a @Component implementing TwinDecisionAgent to replace this with a real model/agent call.
            logger.info("[Twin] Invoking simulated ML agent for activity \"Shipping Twin\" "
                    + "(process instance {})", task.getProcessInstanceId());
            variables = new HashMap<>();
            variables.put("agentTopic", "ShippingTwin");
            variables.put("agentInvocationId", UUID.randomUUID().toString());
            variables.put("agentTimestamp", System.currentTimeMillis());
            logger.info("[Twin] Agent invocation result: {}", variables);
        }
        logger.info("[Twin] Completion variables: {}", variables);
        externalTaskService.complete(task.getId(), "generated-worker", variables);
    }
}
