package com.metaml.nodemanager.service;

import org.springframework.stereotype.Service;

import com.metaml.nodemanager.payload.AgentAvailabilityResponse;

import java.util.Map;

@Service
public class NodeManagerServiceImpl implements NodeManagerService {

    private static final Map<String, String> AGENT_CATALOG = Map.of(
            "data-enricher", "data-enricher-agent-01",
            "notifier", "notifier-agent-01",
            "validator", "validator-agent-01",
            "recommender", "recommender-agent-01");

    @Override
    public AgentAvailabilityResponse checkAvailability(String agentType) {
        String agentName = AGENT_CATALOG.get(agentType);
        if (agentName != null) {
            return new AgentAvailabilityResponse(agentType, true, agentName,
                    "Agent registered in node manager catalog");
        }
        return new AgentAvailabilityResponse(agentType, false, null,
                "Agent type not found in node manager catalog");
    }
}
