package com.metaml.workbench.service;

import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.GovernancePolicy;
import com.metaml.workbench.model.GovernanceUsage;

import java.util.Set;

public interface GovernanceService {

    GovernancePolicy getPolicy();

    GovernancePolicy updatePolicy(Set<String> deniedAgentTypes, Integer maxEvolutionsPerTwin);

    GovernanceDecision reserveEvolutionSlot(String twinProcessId, String agentType);

    void releaseEvolutionSlot(String twinProcessId);

    GovernanceUsage getUsage(String twinProcessId);
}
