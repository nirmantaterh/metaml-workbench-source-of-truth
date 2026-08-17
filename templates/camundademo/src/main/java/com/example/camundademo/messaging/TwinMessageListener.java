package com.example.camundademo.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Coordinates the Twin side of the Manufacturing → Gateway → Manufacturing flow.
 *
 * <pre>
 * Manufacturing → Twin → Gateway → Twin → Manufacturing
 * </pre>
 *
 * <p>Preserves the original correlation ID while waiting for the Gateway QC result.
 */
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class TwinMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(TwinMessageListener.class);

    private final HarnessMessagePublisher publisher;

    public TwinMessageListener(HarnessMessagePublisher publisher) {
        this.publisher = publisher;
    }

    @RabbitListener(queues = MessagingTopology.TWIN_STAGE_UPDATE_QUEUE)
    public void onStageUpdate(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[twin] discarding malformed stage update: {}", message);
            return;
        }
        logger.info("[twin] received stage update for activity '{}' (correlationId={}) - requesting QC",
                message.getActivityId(), message.getCorrelationId());

        HarnessMessage qcRequest = message.reply(HarnessMessage.Type.QC_REQUEST,
                MessagingTopology.COMPONENT_TWIN, MessagingTopology.COMPONENT_GATEWAY, null);
        publisher.publish(MessagingTopology.GATEWAY_EXCHANGE, MessagingTopology.GATEWAY_QC_REQUEST_KEY,
                qcRequest);
    }

    @RabbitListener(queues = MessagingTopology.GATEWAY_QC_RESPONSE_QUEUE)
    public void onQcResponse(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[twin] discarding malformed QC response: {}", message);
            return;
        }
        logger.info("[twin] received QC response '{}' for activity '{}' (correlationId={}) "
                + "- reporting stage result to manufacturing",
                message.getStatus(), message.getActivityId(), message.getCorrelationId());

        HarnessMessage stageResponse = message.reply(HarnessMessage.Type.STAGE_UPDATE_RESPONSE,
                MessagingTopology.COMPONENT_TWIN, MessagingTopology.COMPONENT_MANUFACTURING,
                message.getStatus());
        publisher.publish(MessagingTopology.TWIN_EXCHANGE, MessagingTopology.TWIN_STAGE_RESPONSE_KEY,
                stageResponse);
    }
}
