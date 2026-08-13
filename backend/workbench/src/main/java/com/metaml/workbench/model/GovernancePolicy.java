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
    // separate budget: token steps far outnumber agent requests in any real process
    private int maxTwinExecutionsPerTwin;
}
