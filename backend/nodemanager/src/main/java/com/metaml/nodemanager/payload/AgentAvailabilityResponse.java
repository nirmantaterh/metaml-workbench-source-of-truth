package com.metaml.nodemanager.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAvailabilityResponse {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
    // the agent's own verdict on the work it was asked to do, not a statement about the agent.
    // Named entries rather than one flag, so a project bringing its own agents doesn't need a
    // new field here for whatever its agents report.
    private Map<String, Object> outputs;
}
