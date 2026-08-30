package com.metaml.nodemanager.service;

import com.metaml.nodemanager.payload.AgentAvailabilityResponse;
import java.util.List;

public interface NodeManagerService {
    AgentAvailabilityResponse checkAvailability(String agentType);
    List<AgentAvailabilityResponse> listAvailableAgents();
}
