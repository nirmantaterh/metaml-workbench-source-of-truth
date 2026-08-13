package com.metaml.workbench.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class NodeManagerClient {

    private static final Logger logger = LoggerFactory.getLogger(NodeManagerClient.class);

    private static final String NODE_MANAGER_AGENT_URL = "http://localhost:8083/api/v1/node-manager/agents/{agentType}";

    // agentType goes into the URL path; RestTemplate doesn't escape "/", so restrict the alphabet.
    private static final Pattern SAFE_AGENT_TYPE = Pattern.compile("^[a-z0-9-]+$");

    // output names become Camunda variable names; must be plain camelCase
    private static final Pattern SAFE_OUTPUT_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    // bare RestTemplate never times out; 1s+2s keeps AutoBridgeTrigger's budget above worst-case
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

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
        if (result.isAvailable() && (result.getAgentName() == null || result.getAgentName().isBlank())) {
            return new AgentAvailabilityResult(agentType, false, null,
                    "Node manager reported the agent type as available but named no agent", false);
        }
        result.setOutputs(usableOutputs(agentType, result.getOutputs()));
        return result;
    }

    // Node manager has no auth; drop bad entries rather than failing the whole evolution.
    private static Map<String, Object> usableOutputs(String agentType, Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> usable = new LinkedHashMap<>();
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            String name = output.getKey();
            Object value = output.getValue();
            if (name == null || !SAFE_OUTPUT_NAME.matcher(name).matches()) {
                logger.warn("Dropping output '{}' reported for agent type {}: not a plain camelCase name",
                        name, agentType);
                continue;
            }
            if (!(value instanceof Boolean || value instanceof String || value instanceof Number)) {
                logger.warn("Dropping output '{}' reported for agent type {}: {} is not a scalar",
                        name, agentType, value == null ? "null" : value.getClass().getName());
                continue;
            }
            usable.put(name, value);
        }
        return usable;
    }
}
