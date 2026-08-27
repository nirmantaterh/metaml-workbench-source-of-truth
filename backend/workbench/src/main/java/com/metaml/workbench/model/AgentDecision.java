package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDecision {
    private String agentType;
    private boolean approved;
    private String agentName;
    private String reason;
    private boolean riskFlagged;
    // Tenant governance (Phase 3B): null for every refusal that has nothing to do with tenant policy - quota exceeded, node manager unavailable, activity not connected, all of that stays exactly as it read before. Only set to "DENY" or "REQUIRE_APPROVAL" when the PolicyDecisionEngine is the actual reason this wasn't approved, so a caller (or a test) can tell "governance said no" apart from "something else went wrong" without parsing the reason string.
    private String governanceDecision;

    // most of the paths that build a decision are refusals - no agent ran, so nothing flagged anything. Saves repeating a bare false/null at the many call sites that don't care about either.
    public AgentDecision(String agentType, boolean approved, String agentName, String reason) {
        this(agentType, approved, agentName, reason, false, null);
    }
}
