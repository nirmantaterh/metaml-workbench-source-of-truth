package com.metaml.workbench.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.regex.Pattern;

@Component
public class NodeManagerClient {

    private static final String NODE_MANAGER_AGENT_URL = "http://localhost:8083/api/v1/node-manager/agents/{agentType}";

    // agentType is free text off the /evolve request body and gets expanded straight into the
    // URL path above. RestTemplate's default encoding does not escape "/" inside a path
    // variable, so an unconstrained value could steer the outbound request at an arbitrary
    // node manager path -- restrict it to the shape real catalog keys actually have.
    private static final Pattern SAFE_AGENT_TYPE = Pattern.compile("^[a-z0-9-]+$");

    private final RestTemplate restTemplate = new RestTemplate();

    public AgentAvailabilityResult checkAgentAvailability(String agentType) {
        if (agentType == null || !SAFE_AGENT_TYPE.matcher(agentType).matches()) {
            throw new IllegalArgumentException(
                    "Agent type must consist of lowercase letters, digits and hyphens only (was: "
                            + agentType + ")");
        }

        AgentAvailabilityResult result;
        try {
            result = restTemplate.getForObject(NODE_MANAGER_AGENT_URL, AgentAvailabilityResult.class, agentType);
        } catch (RestClientException e) {
            throw new NodeManagerUnavailableException(
                    "Could not reach node manager for agent type " + agentType + ": " + e.getMessage());
        }
        if (result == null) {
            throw new NodeManagerUnavailableException(
                    "Node manager returned an empty response for agent type " + agentType);
        }
        return result;
    }
}
