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

    // most of the paths that build a decision are refusals - no agent ran, so nothing flagged
    // anything. Saves repeating a bare false at ten call sites that don't care about it.
    public AgentDecision(String agentType, boolean approved, String agentName, String reason) {
        this(agentType, approved, agentName, reason, false);
    }
}
