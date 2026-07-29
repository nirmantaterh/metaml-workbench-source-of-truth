package com.metaml.nodemanager.service;

import org.springframework.stereotype.Service;

import com.metaml.nodemanager.config.NodeManagerProperties;
import com.metaml.nodemanager.payload.AgentAvailabilityResponse;

import java.util.Map;

@Service
public class NodeManagerServiceImpl implements NodeManagerService {

    private final NodeManagerProperties properties;

    public NodeManagerServiceImpl(NodeManagerProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentAvailabilityResponse checkAvailability(String agentType) {
        NodeManagerProperties.AgentConfig agent = properties.getAgents().get(agentType);
        if (agent != null && agent.getAgentName() != null) {
            return new AgentAvailabilityResponse(agentType, true, agent.getAgentName(),
                    "Agent registered in node manager catalog",
                    agent.getOutputs() == null ? Map.of() : agent.getOutputs());
        }
        return new AgentAvailabilityResponse(agentType, false, null,
                "Agent type not found in node manager catalog", Map.of());
    }
}
