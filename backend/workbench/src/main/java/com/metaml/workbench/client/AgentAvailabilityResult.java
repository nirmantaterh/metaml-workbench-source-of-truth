package com.metaml.workbench.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.metaml.workbench.model.AgentVariables;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAvailabilityResult {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
    private Map<String, Object> outputs;

    // the shape this had back when a raised risk flag was the only thing an agent could say. Plenty of callers still only care about that one, and the stubbed catalogs in the tests are written against it.
    public AgentAvailabilityResult(String agentType, boolean available, String agentName,
            String reason, boolean riskFlagged) {
        this(agentType, available, agentName, reason,
                riskFlagged ? Map.of(AgentVariables.RISK_FLAGGED_OUTPUT, true) : Map.of());
    }

    // derived, not stored - two places holding the same fact is how they end up disagreeing
    public boolean isRiskFlagged() {
        return outputs != null && Boolean.TRUE.equals(outputs.get(AgentVariables.RISK_FLAGGED_OUTPUT));
    }
}
