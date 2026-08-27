package com.metaml.workbench.governance;

import java.time.Instant;

// Immutable evaluation result produced by the policy decision engine.
public record PolicyDecision(PolicyEffect decision, String tenantId, String policyId, String policyVersionId,
        Integer policyVersionNumber, String matchedRuleId, String reason, Instant evaluatedAt) {
}
