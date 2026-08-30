package com.tp.TargetPlatform.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// The real consumer for response messages - the Camunda signal delivery that releases proxy's waiting execution happens here, triggered by consuming the message.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class ResponseQueueListener {

    // No signal is shared between proxy and twin in this project's BPMNs, so there is
    // nothing to consume here.

}
