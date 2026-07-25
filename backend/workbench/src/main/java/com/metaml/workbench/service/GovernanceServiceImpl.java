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

    // Low enough to hit by clicking "Evolve" a few times in the demo UI, not a considered
    // production default.
    private static final int DEFAULT_MAX_EVOLUTIONS_PER_TWIN = 3;

    // A whole-set swap (not a mutable ConcurrentHashMap.newKeySet() mutated via clear()+
    // addAll()) so a concurrent getPolicy() can never observe a torn state mid-update.
    private volatile Set<String> deniedAgentTypes = Set.of();
    private volatile int maxEvolutionsPerTwin = DEFAULT_MAX_EVOLUTIONS_PER_TWIN;
    private final Map<String, AtomicInteger> evolutionCounts = new ConcurrentHashMap<>();

    @Override
    public GovernancePolicy getPolicy() {
        return new GovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin);
    }

    @Override
    public GovernancePolicy updatePolicy(Set<String> newDeniedAgentTypes, Integer newMaxEvolutionsPerTwin) {
        // Both fields are independently optional so a caller can update just the quota, or
        // just the denylist, without needing to first fetch and echo back the other's
        // current value.
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

    // Denylist entries are free text from a human-edited form field -- trim/lowercase both
    // here and at match time so "Validator" in the policy actually blocks a request for
    // "validator" (the real, exact-case node manager catalog key).
    private static Set<String> normalize(Set<String> agentTypes) {
        return agentTypes.stream()
                .map(type -> type.trim().toLowerCase())
                .filter(type -> !type.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // Denylist and quota are checked and (for the quota) reserved atomically in one call,
    // instead of a separate check-then-record pair: two concurrent requests for the same
    // twin process arriving at count == max-1 could otherwise both pass a read-only check
    // before either recorded its evolution, letting the quota be exceeded. Reserving via
    // incrementAndGet() up front and rolling back with decrementAndGet() if that puts the
    // twin over quota closes that race -- only one caller can ever win the last slot.
    @Override
    public GovernanceDecision reserveEvolutionSlot(String twinProcessId, String agentType) {
        if (deniedAgentTypes.contains(agentType.trim().toLowerCase())) {
            return new GovernanceDecision(false,
                    "Agent type '" + agentType + "' is denied by governance policy");
        }

        // Read the volatile quota once: comparing against it and then reading it again to
        // build the message could otherwise straddle a concurrent updatePolicy and report a
        // limit that isn't the one the request was actually rejected against.
        int max = maxEvolutionsPerTwin;
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

    // Called when a reserved slot's evolution did NOT end up succeeding (node manager
    // unreachable or reported the agent unavailable) -- a request blocked before reservation
    // (by the denylist, or by governance's own quota check) never touches the counter and so
    // never needs releasing.
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
