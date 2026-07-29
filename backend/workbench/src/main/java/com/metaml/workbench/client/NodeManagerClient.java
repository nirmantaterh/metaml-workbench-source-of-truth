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

    // agentType is free text from the request body and goes straight into the path above.
    // RestTemplate doesn't escape "/" in a path variable, so restrict it.
    private static final Pattern SAFE_AGENT_TYPE = Pattern.compile("^[a-z0-9-]+$");

    // output names get pasted into generated Camunda variable names on both sides of the bridge,
    // and the code that builds those names assumes a plain camelCase identifier. Same reasoning
    // as SAFE_AGENT_TYPE, different alphabet.
    private static final Pattern SAFE_OUTPUT_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    // a bare RestTemplate never times out - one stuck call here and the auto-bridge's single
    // thread never bridges anything again. Was 2s/5s, which put the worst case at 7s and forced
    // AutoBridgeTrigger's wait to be at least that long; a multi-instance activity pays that per
    // visit, so it's now 3s worst case for what is a call to localhost. Anything slower than
    // this isn't a stub having a bad day, it's something actually broken.
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
        // available with no agent name is nonsense, and it used to come back as an approved decision with a null agent that then got written into evolvedAgent_*
        if (result.isAvailable() && (result.getAgentName() == null || result.getAgentName().isBlank())) {
            return new AgentAvailabilityResult(agentType, false, null,
                    "Node manager reported the agent type as available but named no agent", false);
        }
        result.setOutputs(usableOutputs(agentType, result.getOutputs()));
        return result;
    }

    // The node manager is a stub with no authentication in front of it, so its answers get the
    // same treatment as anything else off the wire: drop what doesn't fit the contract and carry
    // on with the rest. Throwing here would take down an evolution over one odd catalog entry.
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
            // anything richer than a scalar can't survive the trip into a process variable in a
            // shape a gateway condition could read back
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
