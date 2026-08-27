package com.example.camundademo.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.camundademo.messaging.HarnessMessage;
import com.example.camundademo.messaging.HarnessMessagePublisher;
import com.example.camundademo.messaging.MessagingTopology;

// What the generated controllers call after an activity completes, to tell the other side. <p>The seam between Camunda-driven execution and RabbitMQ-carried communication. Never touches the engine itself - Camunda owns process execution, this only reports on it. <p>The log line fires whether or not messaging is enabled, so "this side notified the other" stays observable with no broker configured; publish is what actually carries the message.
@Component
public class NotificationBridge {

    private static final Logger logger = LoggerFactory.getLogger(NotificationBridge.class);

    private final HarnessMessagePublisher publisher;

    public NotificationBridge(HarnessMessagePublisher publisher) {
        this.publisher = publisher;
    }

    // Manufacturing finished an activity: tell the twin, and ask it to run QC. <p>Starts a new correlation id, propagated by the twin through the gateway and back, so the whole round trip shares one id.
    public void notifyTwin(String processInstanceId, String activityId) {
        logger.info("[manufacturing -> twin] activity '{}' complete - notifying twin", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.STAGE_UPDATE_REQUEST,
                MessagingTopology.COMPONENT_MANUFACTURING, MessagingTopology.COMPONENT_TWIN,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.TWIN_EXCHANGE, MessagingTopology.TWIN_STAGE_UPDATE_KEY,
                message);
    }

    // The twin side finished an activity of its own: report it to manufacturing. <p>Distinct from the twin's automatic reply inside the QC chain - this is the twin's own generated controller reporting a directly-invoked twin activity.
    public void notifyManufacturing(String processInstanceId, String activityId) {
        logger.info("[twin -> manufacturing] activity '{}' complete - notifying manufacturing", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.STAGE_UPDATE_RESPONSE,
                MessagingTopology.COMPONENT_TWIN, MessagingTopology.COMPONENT_MANUFACTURING,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.TWIN_EXCHANGE, MessagingTopology.TWIN_STAGE_RESPONSE_KEY,
                message);
    }

    // Manufacturing needs machines acquired for an activity before it can proceed. <p>Not called by generated code yet - which activities need machines is a process-specific decision (RedCollar), not something the generic generator can know. Exposed so the machines flow has a real entry point once such a model exists.
    public void requestMachines(String processInstanceId, String activityId) {
        logger.info("[manufacturing -> machines] requesting machines for activity '{}'", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.MACHINE_REQUEST,
                MessagingTopology.COMPONENT_MANUFACTURING, MessagingTopology.COMPONENT_MACHINES,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.MACHINES_EXCHANGE, MessagingTopology.MACHINES_REQUEST_KEY,
                message);
    }
}
