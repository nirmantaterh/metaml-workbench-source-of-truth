package com.tp.TargetPlatform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Publishes "proxy is ready to advance past this signal" to that signal's own dedicated task queue. TaskQueueListener performs the actual Camunda signal delivery that releases twin's waiting execution, on consume. Always present as a bean, but isEnabled() is false unless metaml.messaging.enabled=true. Reliability hardening (Pass 1): publish() now blocks on a publisher confirm (rabbitTemplate.invoke + waitForConfirmsOrDie, which requires spring.rabbitmq.publisher-confirm-type=simple - see this project's application.properties) before returning or logging success. SignalBroadcaster.deliverTo() only marks a signal as everDelivered AFTER publish() returns normally, so a NACKed or unconfirmed publish throws here, deliverTo() never marks it delivered, and the next broadcaster tick simply retries - this preserves the existing "safe to re-attempt" behavior rather than adding a second, separate retry mechanism on top of it.
@Component
public class TaskQueuePublisher {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueuePublisher.class);

    // Long enough for a broker under normal load to ack/nack; short enough that a genuinely unreachable broker fails this attempt and lets the next broadcaster tick (1s later) retry, rather than blocking the single-threaded scheduler indefinitely.
    private static final long CONFIRM_TIMEOUT_MS = 5000L;

    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;

    public TaskQueuePublisher(RabbitTemplate rabbitTemplate,
            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEligible(String signalName) {
        return RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
    }

    public void publish(String signalName, String executionId, String processInstanceId,
            String businessKey) {
        String routingKey = RabbitMqConfig.TASK_ROUTING_KEY_BY_SIGNAL.get(signalName);
        if (routingKey == null) {
            throw new IllegalArgumentException("No task queue is declared for signal '"
                    + signalName + "' - callers must check isEligible(signalName) first");
        }
        String payload = signalName + "|" + executionId + "|" + processInstanceId
                + "|" + (businessKey == null ? "" : businessKey);
        try {
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload,
                        message -> {
                            message.getMessageProperties()
                                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return message;
                        });
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                return null;
            }, null, null);
        } catch (RuntimeException e) {
            logger.error("TASK: publish NOT confirmed for signal '{}' execution {} "
                    + "(processInstanceId={}, businessKey={}): {}", signalName, executionId,
                    processInstanceId, businessKey, e.toString());
            throw e;
        }
        logger.info("TASK: published signal '{}' to RabbitMQ exchange '{}' key '{}' for "
                + "execution {} (processInstanceId={}, businessKey={})", signalName,
                RabbitMqConfig.EXCHANGE, routingKey, executionId, processInstanceId, businessKey);
    }
}
