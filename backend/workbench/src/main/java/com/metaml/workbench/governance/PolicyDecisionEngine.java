package com.metaml.workbench.governance;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Phase 2 (see the Phase 1 tenant/policy work and the Phase 0 architecture audit): the one
// place that turns a persisted tenant policy into ALLOW/DENY/REQUIRE_APPROVAL for a single
// request. It only decides - nothing here pauses, blocks, or executes anything. A later phase
// wires a real call site (and eventually a Policy Simulator) to this same engine; there is
// deliberately no second evaluator anywhere in the codebase.
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
        // fail loud on an unknown tenant - that's a bad reference from the caller, not an
        // "ungoverned action" (that only applies once the tenant is real and simply has no
        // matching rule)
        tenantPolicyService.getTenant(request.tenantId());

        Optional<RuleMatch> platformMatch = bestMatch(tenantPolicyService.listPlatformPolicies(), request);
        Optional<RuleMatch> tenantMatch = bestMatch(tenantPolicyService.listTenantPolicies(request.tenantId()),
                request);
        Optional<RuleMatch> winner = pickWinner(platformMatch, tenantMatch);

        if (winner.isEmpty()) {
            // nothing matched, platform or tenant - the action is simply ungoverned. Default
            // is ALLOW: silence in the policy must never turn into a block on work nobody
            // ever wrote a rule about.
            return new PolicyDecision(PolicyEffect.ALLOW, request.tenantId(), null, null, null, null,
                    "No matching rule (platform or tenant) - action is ungoverned, default ALLOW",
                    Instant.now());
        }
        RuleMatch match = winner.get();
        return new PolicyDecision(match.rule().effect(), request.tenantId(), match.policy().id(),
                match.version().id(), match.version().versionNumber(), match.rule().id(), describe(match),
                Instant.now());
    }

    // platform policies are the ones with tenantId==null, so the exact same lookup serves both
    // callers below - only the input list differs
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
        // the first rule in the version's own list wins - that's the order rules were added
        // in (addRule appends), so this is the version's own declared precedence, not an
        // accident of some unrelated collection's iteration order
        for (PolicyRule rule : active.get().rules()) {
            if (matches(rule, request)) {
                return Optional.of(new RuleMatch(policy, active.get(), rule));
            }
        }
        return Optional.empty();
    }

    // platform must never be quietly overridden by an equally-or-less restrictive tenant
    // decision - a tied severity is deliberately resolved in the platform's favor
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
        // the field just isn't part of this request - a normal non-match, not an error. A
        // real error is an operator or a stored rule value this engine can't interpret at
        // all, handled below, and that must never be swallowed into a silent skip.
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

    // numeric equality if both sides parse as numbers, exact string equality otherwise - lets
    // "action == DELETE_CUSTOMER" work without a separate string-only operator
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
