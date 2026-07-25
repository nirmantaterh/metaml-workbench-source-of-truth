package com.metaml.nodemanager.service;

import com.metaml.nodemanager.payload.AgentAvailabilityResponse;

public interface NodeManagerService {
    AgentAvailabilityResponse checkAvailability(String agentType);
}
