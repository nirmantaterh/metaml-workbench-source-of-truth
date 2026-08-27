package com.metaml.workbench.governance;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Evaluates governance requests against platform and tenant policies.
@Component
public class PolicyDecisionEngine {

    private final TenantPolicyService tenantPolicyService;

    public PolicyDecisionEngine(TenantPolicyService tenantPolicyService) {
        this.tenantPolicyService = tenantPolicyService;
    }

    public PolicyDecision evaluate(GovernanceRequest request) {
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required to evaluate a request");
        }
        // Fail loud on unknown tenant.
        tenantPolicyService.getTenant(request.tenantId());

        Optional<RuleMatch> platformMatch = bestMatch(tenantPolicyService.listPlatformPolicies(), request);
        Optional<RuleMatch> tenantMatch = bestMatch(tenantPolicyService.listTenantPolicies(request.tenantId()),
                request);
        Optional<RuleMatch> winner = pickWinner(platformMatch, tenantMatch);

        if (winner.isEmpty()) {
            // Ungoverned actions default to ALLOW.
            return new PolicyDecision(PolicyEffect.ALLOW, request.tenantId(), null, null, null, null,
                    "No matching rule (platform or tenant) - action is ungoverned, default ALLOW",
                    Instant.now());
        }
        RuleMatch match = winner.get();
        return new PolicyDecision(match.rule().effect(), request.tenantId(), match.policy().id(),
                match.version().id(), match.version().versionNumber(), match.rule().id(), describe(match),
                Instant.now());
    }

    // Finds best matching rule in a policy list.
    private Optional<RuleMatch> bestMatch(List<Policy> policies, GovernanceRequest request) {
        return policies.stream()
                .sorted(Comparator.comparing(Policy::id)) // deterministic order, not map iteration order
                .map(policy -> firstMatch(policy, request))
                .flatMap(Optional::stream)
                .max(Comparator.comparingInt(m -> severity(m.rule().effect())));
    }

    private Optional<RuleMatch> firstMatch(Policy policy, GovernanceRequest request) {
        Optional<PolicyVersion> active = tenantPolicyService.getActiveVersion(policy.id());
        if (active.isEmpty()) {
            return Optional.empty();
        }
        // Evaluates rules in order of declaration.
        for (PolicyRule rule : active.get().rules()) {
            if (matches(rule, request)) {
                return Optional.of(new RuleMatch(policy, active.get(), rule));
            }
        }
        return Optional.empty();
    }

    // Platform policy takes precedence on tied severity.
    private Optional<RuleMatch> pickWinner(Optional<RuleMatch> platform, Optional<RuleMatch> tenant) {
        if (platform.isEmpty()) {
            return tenant;
        }
        if (tenant.isEmpty()) {
            return platform;
        }
        int platformSeverity = severity(platform.get().rule().effect());
        int tenantSeverity = severity(tenant.get().rule().effect());
        return platformSeverity >= tenantSeverity ? platform : tenant;
    }

    private static int severity(PolicyEffect effect) {
        return switch (effect) {
            case DENY -> 2;
            case REQUIRE_APPROVAL -> 1;
            case ALLOW -> 0;
        };
    }

    private boolean matches(PolicyRule rule, GovernanceRequest request) {
        Object actual = "action".equals(rule.field()) ? request.action() : request.attributes().get(rule.field());
        // Return false if attribute missing.
        if (actual == null) {
            return false;
        }
        return switch (rule.operator()) {
            case "==" -> valuesEqual(actual, rule.value());
            case "!=" -> !valuesEqual(actual, rule.value());
            case ">", ">=", "<", "<=" -> numericCompare(rule, actual);
            default -> throw new PolicyEvaluationException(
                    "Unsupported operator '" + rule.operator() + "' in rule " + rule.id());
        };
    }

    private boolean numericCompare(PolicyRule rule, Object actual) {
        Double ruleNumber = asNumber(rule.value());
        if (ruleNumber == null) {
            throw new PolicyEvaluationException("Rule " + rule.id() + " uses operator '" + rule.operator()
                    + "' but its value '" + rule.value() + "' is not numeric");
        }
        Double actualNumber = asNumber(actual);
        if (actualNumber == null) {
            throw new PolicyEvaluationException("Field '" + rule.field() + "' value '" + actual
                    + "' is not numeric, required by operator '" + rule.operator() + "' in rule " + rule.id());
        }
        return switch (rule.operator()) {
            case ">" -> actualNumber > ruleNumber;
            case ">=" -> actualNumber >= ruleNumber;
            case "<" -> actualNumber < ruleNumber;
            case "<=" -> actualNumber <= ruleNumber;
            default -> throw new IllegalStateException("unreachable - caller already filtered to these four");
        };
    }

    // Compares numeric equality if both parse as numbers, string equality otherwise.
    private boolean valuesEqual(Object actual, String ruleValue) {
        Double actualNumber = asNumber(actual);
        Double ruleNumber = asNumber(ruleValue);
        if (actualNumber != null && ruleNumber != null) {
            return actualNumber.doubleValue() == ruleNumber.doubleValue();
        }
        return String.valueOf(actual).equals(ruleValue);
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String describe(RuleMatch match) {
        return "Rule " + match.rule().field() + " " + match.rule().operator() + " " + match.rule().value()
                + " matched on policy '" + match.policy().name() + "' (version " + match.version().versionNumber()
                + (match.policy().isPlatform() ? ", platform" : ", tenant " + match.policy().tenantId()) + ")";
    }

    private record RuleMatch(Policy policy, PolicyVersion version, PolicyRule rule) {
    }
}
