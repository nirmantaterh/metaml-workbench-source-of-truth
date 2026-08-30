package com.metaml.nodemanager.service;

import org.springframework.stereotype.Service;

import com.metaml.nodemanager.config.NodeManagerProperties;
import com.metaml.nodemanager.payload.AgentAvailabilityResponse;

import java.util.ArrayList;
import java.util.List;
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
                    agent.getOutputs() == null ? Map.of() : agent.getOutputs(),
                    agent.getDescription() != null ? agent.getDescription() : "Registered agent component",
                    agent.getCapabilities() != null ? agent.getCapabilities() : List.of());
        }
        return new AgentAvailabilityResponse(agentType, false, null,
                "Agent type not found in node manager catalog", Map.of(), null, List.of());
    }

    @Override
    public List<AgentAvailabilityResponse> listAvailableAgents() {
        List<AgentAvailabilityResponse> list = new ArrayList<>();
        for (Map.Entry<String, NodeManagerProperties.AgentConfig> entry : properties.getAgents().entrySet()) {
            String type = entry.getKey();
            NodeManagerProperties.AgentConfig config = entry.getValue();
            if (config != null && config.getAgentName() != null) {
                list.add(new AgentAvailabilityResponse(
                        type,
                        true,
                        config.getAgentName(),
                        "Agent registered in node manager catalog",
                        config.getOutputs() == null ? Map.of() : config.getOutputs(),
                        config.getDescription() != null ? config.getDescription() : "Registered agent component",
                        config.getCapabilities() != null ? config.getCapabilities() : List.of()
                ));
            }
        }
        return list;
    }
}
