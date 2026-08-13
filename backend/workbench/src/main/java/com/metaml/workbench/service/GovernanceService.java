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

    // twin advances draw from a separate budget; automation must not exhaust the agent-request quota
    GovernanceDecision reserveTwinExecutionSlot(String twinProcessId);

    void releaseTwinExecutionSlot(String twinProcessId);

    GovernanceUsage getUsage(String twinProcessId);
}
