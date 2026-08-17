package com.example.camundademo.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central message publisher for the Target Harness Platform.
 *
 * <p>Publishes through exchanges and routing keys. When messaging is disabled,
 * logs the message without opening a broker connection.
 */
@Component
public class HarnessMessagePublisher {

    private static final Logger logger = LoggerFactory.getLogger(HarnessMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;

    public HarnessMessagePublisher(RabbitTemplate rabbitTemplate,
            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Publishes a message without allowing broker failures to interrupt Camunda work.
     *
     * <p>Failures are logged and swallowed; no retry mechanism is used.
     */
    public void publish(String exchange, String routingKey, HarnessMessage message) {
        if (!enabled) {
            logger.info("[messaging disabled] would publish {} to exchange '{}' key '{}'",
                    message, exchange, routingKey);
            return;
        }
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
            logger.info("[published] {} -> exchange '{}' key '{}' (messageId={})",
                    message, exchange, routingKey, message.getMessageId());
        } catch (AmqpException e) {
            logger.error("[publish FAILED] {} -> exchange '{}' key '{}' (correlationId={}): {}",
                    message, exchange, routingKey, message.getCorrelationId(), e.toString(), e);
        }
    }
}
