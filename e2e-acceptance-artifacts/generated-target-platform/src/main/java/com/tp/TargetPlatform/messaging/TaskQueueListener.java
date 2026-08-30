package com.tp.TargetPlatform.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// The real consumer for task messages - the Camunda signal delivery that releases twin's waiting execution happens here, triggered by consuming the message. Enabled only with metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly instead.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class TaskQueueListener {

    // No signal is shared between proxy and twin in this project's BPMNs, so there is
    // nothing to consume here.

}
