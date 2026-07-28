package com.metaml.workbench.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class NodeManagerClient {

    private static final String NODE_MANAGER_AGENT_URL = "http://localhost:8083/api/v1/node-manager/agents/{agentType}";

    // agentType is free text from the request body and goes straight into the path above.
    // RestTemplate doesn't escape "/" in a path variable, so restrict it.
    private static final Pattern SAFE_AGENT_TYPE = Pattern.compile("^[a-z0-9-]+$");

    // a bare RestTemplate never times out - one stuck call here and the auto-bridge's single
    // thread never bridges anything again. both stay under AutoBridgeTrigger's 10s wait.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestTemplate restTemplate = new RestTemplate(requestFactory());

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

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
        // available with no agent name is nonsense, and it used to come back as an approved decision with a null agent that then got written into evolvedAgent_*
        if (result.isAvailable() && (result.getAgentName() == null || result.getAgentName().isBlank())) {
            return new AgentAvailabilityResult(agentType, false, null,
                    "Node manager reported the agent type as available but named no agent");
        }
        return result;
    }
}
