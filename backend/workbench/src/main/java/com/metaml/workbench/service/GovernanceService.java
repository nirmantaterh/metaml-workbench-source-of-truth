package com.metaml.workbench.service;

import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.GovernancePolicy;
import com.metaml.workbench.model.GovernanceUsage;

import java.util.Set;

public interface GovernanceService {

    GovernancePolicy getPolicy();

    GovernancePolicy updatePolicy(Set<String> deniedAgentTypes, Integer maxEvolutionsPerTwin);

    GovernancePolicy updatePolicy(Set<String> deniedAgentTypes, Integer maxEvolutionsPerTwin,
            Integer maxTwinExecutionsPerTwin);

    GovernanceDecision reserveEvolutionSlot(String twinProcessId, String agentType);

    void releaseEvolutionSlot(String twinProcessId);

    // The twin advancing its own token is not an evolution and must not come out of the same
    // budget. The citi walkthrough alone spends seven evolution slots on auto-bridging, and a twin
    // takes a step for every activity it passes through - put them together and the quota that is
    // supposed to be limiting agent requests gets emptied by ordinary automation instead.
    GovernanceDecision reserveTwinExecutionSlot(String twinProcessId);

    void releaseTwinExecutionSlot(String twinProcessId);

    GovernanceUsage getUsage(String twinProcessId);
}
