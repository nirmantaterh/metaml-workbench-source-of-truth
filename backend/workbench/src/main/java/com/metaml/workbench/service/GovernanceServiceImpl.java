package com.metaml.workbench.service;

import org.springframework.stereotype.Service;

import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.GovernancePolicy;
import com.metaml.workbench.model.GovernanceUsage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class GovernanceServiceImpl implements GovernanceService {

    // kept low so the quota is easy to hit in a demo
    private static final int DEFAULT_MAX_EVOLUTIONS_PER_TWIN = 3;

    // swap the whole set rather than mutating one, otherwise getPolicy() can catch it half-updated
    private volatile Set<String> deniedAgentTypes = Set.of();
    private volatile int maxEvolutionsPerTwin = DEFAULT_MAX_EVOLUTIONS_PER_TWIN;
    private final Map<String, AtomicInteger> evolutionCounts = new ConcurrentHashMap<>();

    @Override
    public GovernancePolicy getPolicy() {
        return new GovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin);
    }

    @Override
    public GovernancePolicy updatePolicy(Set<String> newDeniedAgentTypes, Integer newMaxEvolutionsPerTwin) {
        // both optional so you can update just one of them
        if (newDeniedAgentTypes != null) {
            deniedAgentTypes = normalize(newDeniedAgentTypes);
        }
        if (newMaxEvolutionsPerTwin != null) {
            if (newMaxEvolutionsPerTwin < 0) {
                throw new IllegalArgumentException("maxEvolutionsPerTwin must not be negative");
            }
            maxEvolutionsPerTwin = newMaxEvolutionsPerTwin;
        }
        return getPolicy();
    }

    // denylist comes straight from a text box, so trim/lowercase here and at match time
    private static Set<String> normalize(Set<String> agentTypes) {
        return agentTypes.stream()
                .map(type -> type.trim().toLowerCase())
                .filter(type -> !type.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // Increment first and roll back if we went over, instead of check-then-increment: with a
    // separate check, two requests sitting at max-1 can both pass it and both take the last slot.
    @Override
    public GovernanceDecision reserveEvolutionSlot(String twinProcessId, String agentType) {
        if (deniedAgentTypes.contains(agentType.trim().toLowerCase())) {
            return new GovernanceDecision(false,
                    "Agent type '" + agentType + "' is denied by governance policy");
        }

        int max = maxEvolutionsPerTwin; // read once so the message matches what we compared against
        AtomicInteger counter = evolutionCounts.computeIfAbsent(twinProcessId, id -> new AtomicInteger());
        int reserved = counter.incrementAndGet();
        if (reserved > max) {
            counter.decrementAndGet();
            return new GovernanceDecision(false,
                    "Evolution quota exceeded for twin process " + twinProcessId
                            + " (" + max + "/" + max + ")");
        }
        return new GovernanceDecision(true, "Allowed by governance policy");
    }

    // only for slots we actually reserved - denylist/quota blocks never touch the counter
    @Override
    public void releaseEvolutionSlot(String twinProcessId) {
        AtomicInteger counter = evolutionCounts.get(twinProcessId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    @Override
    public GovernanceUsage getUsage(String twinProcessId) {
        AtomicInteger count = evolutionCounts.get(twinProcessId);
        return new GovernanceUsage(twinProcessId, count == null ? 0 : count.get(), maxEvolutionsPerTwin);
    }
}
