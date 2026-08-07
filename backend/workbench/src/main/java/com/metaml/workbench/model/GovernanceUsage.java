package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceUsage {
    private String twinProcessId;
    private int evolutionCount;
    private int maxEvolutionsPerTwin;
    private int twinExecutionCount;
    private int maxTwinExecutionsPerTwin;
}
