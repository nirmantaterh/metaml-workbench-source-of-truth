package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;

import java.util.Set;

/**
 * Pluggable executable component contract for evolved Twin Process activities.
 * Implementing classes provide the exact runtime logic and produce component-specific results.
 */
public interface ComponentExecutor {

    /**
     * The primary authoritative agent type this executor handles (e.g. "credit-risk-assessor", "validator").
     */
    String getHandledAgentType();

    /**
     * The exact set of agent names resolved by Node Manager that this executor handles (e.g. Set.of("credit-risk-agent-01")).
     */
    default Set<String> getHandledAgentNames() {
        return Set.of();
    }

    /**
     * Whether this executor handles the specified agent type or exact resolved agent name.
     * Evaluates exact, case-insensitive equality against getHandledAgentType() and getHandledAgentNames().
     * Substring / fuzzy matching is strictly prohibited to guarantee deterministic identity.
     */
    default boolean handles(String agentTypeOrName) {
        if (agentTypeOrName == null || agentTypeOrName.isBlank()) {
            return false;
        }
        String trimmed = agentTypeOrName.trim();
        if (trimmed.equalsIgnoreCase(getHandledAgentType())) {
            return true;
        }
        for (String name : getHandledAgentNames()) {
            if (trimmed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes the component logic against the Twin's execution context and returns the computed result.
     */
    AutomationResult execute(DelegateExecution execution, String activityId, String agentName);
}
