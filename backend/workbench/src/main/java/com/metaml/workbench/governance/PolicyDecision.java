package com.metaml.workbench.governance;

import java.time.Instant;

// Phase 2: the answer, and enough to explain it later without trusting that today's active
// version is still the one that produced it. policyVersionId is the immutable identity; the
// human-readable versionNumber rides along since that's what an admin actually recognizes.
// policyId/policyVersionId/matchedRuleId are all null on a no-match decision - there's no rule
// to point at.
public record PolicyDecision(PolicyEffect decision, String tenantId, String policyId, String policyVersionId,
        Integer policyVersionNumber, String matchedRuleId, String reason, Instant evaluatedAt) {
}
