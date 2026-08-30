package com.tp.TargetPlatform.messaging;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// The real consumer for task messages - the Camunda signal delivery that releases twin's waiting execution happens here, triggered by consuming the message. Enabled only with metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly instead.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class TaskQueueListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueueListener.class);

    private final RuntimeService runtimeService;

    public TaskQueueListener(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    // Reliability hardening (Pass 1): a malformed payload used to be logged and silently dropped (acked as if processed). It now throws instead, so spring.rabbitmq.listener.simple.retry.* retries it (pointlessly, since a malformed payload never becomes valid, but consistently with every other failure path below) and then dead-letters it to RabbitMqConfig.DLQ_TASKS_QUEUE once retries are exhausted - observable there and in this log line, rather than disappearing.
    @RabbitListener(queues = { "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.sampling-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.laying-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.marking-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.cutting-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.stitching-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.checking-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.pressing-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.packaging-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.shipping-signal" })
    public void onTaskMessage(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            logger.error("[task-queue] malformed message, routing to DLQ: {}", payload);
            throw new IllegalArgumentException(
                    "Malformed task-queue payload (expected 4 '|'-delimited fields): "
                            + payload);
        }
        String signalName = parts[0];
        String executionId = parts[1];
        String processInstanceId = parts[2];
        String businessKey = parts[3];
        try {
            runtimeService.signalEventReceived(signalName, executionId);
            logger.info("TASK: delivered signal '{}' to execution {} (processInstanceId={}, "
                    + "businessKey={}) via RabbitMQ", signalName, executionId,
                    processInstanceId, businessKey);
        } catch (ProcessEngineException e) {
            // Reliability hardening (Pass 1): distinguishes the expected, harmless cases - this execution already advanced past signalName (still active, but subscribed to something else now: "has not subscribed") or has completed/gone entirely (execution id no longer exists at all: "cannot find execution") - a genuine redelivery of an already-consumed message, or a rework-loop revisit, either way - from every other Camunda failure, which must NOT be swallowed the same way. Camunda has no single dedicated exception subtype covering both; message text is the only signal for either, same as the pre-hardening code relied on implicitly via a blanket catch.
            if (isAlreadyAdvanced(e)) {
                logger.info("TASK: signal '{}' delivery to execution {} skipped - "
                        + "already advanced past this signal (processInstanceId={}, "
                        + "businessKey={}): {}", signalName, executionId, processInstanceId,
                        businessKey, e.toString());
            } else {
                logger.error("TASK: signal '{}' delivery to execution {} FAILED "
                        + "(processInstanceId={}, businessKey={}): {}", signalName,
                        executionId, processInstanceId, businessKey, e.toString());
                throw e;
            }
        }
    }

    private static boolean isAlreadyAdvanced(ProcessEngineException e) {
        String message = e.getMessage();
        return message != null && (message.contains("has not subscribed")
                || message.contains("Cannot find execution"));
    }

}
