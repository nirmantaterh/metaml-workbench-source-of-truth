package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernancePolicy {
    private Set<String> deniedAgentTypes;
    private int maxEvolutionsPerTwin;
    // separate budget from the one above, not a second name for it. An evolution is a call out to
    // the node manager for an agent; a twin execution is the twin's own token taking one step, and
    // a process of any size takes far more of those than it asks for agents.
    private int maxTwinExecutionsPerTwin;
}
