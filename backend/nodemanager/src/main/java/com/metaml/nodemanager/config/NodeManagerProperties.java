package com.metaml.nodemanager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The catalog used to be a Map.of() in the service, which meant every project attaching its own agent types needed this module recompiled and redeployed. Config instead, keyed by agent type.
@Data
@ConfigurationProperties(prefix = "nodemanager")
public class NodeManagerProperties {

    private Map<String, AgentConfig> agents = new LinkedHashMap<>();

    @Data
    public static class AgentConfig {
        private String agentName;
        private String description;
        private List<String> capabilities = new ArrayList<>();
        // what this agent reports back about the work, not about itself. Values stay whatever type the yaml says they are: a gateway comparing against true has no time for the string "true".
        private Map<String, Object> outputs = new LinkedHashMap<>();
    }
}
