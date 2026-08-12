package com.metaml.workbench.governance;

import java.time.Instant;

// Phase 4: the persisted record of one REQUIRE_APPROVAL decision, and everything needed to
// safely resume the exact operation it paused - not just tenantId/action, which wouldn't be
// enough to know WHICH twin activity to actually run. Immutable, same as PolicyVersion -
// resolving one replaces the map entry with a new Approval rather than mutating this one, so a
// reference already read by a caller can never observe a half-updated status.
//
// policyId/policyVersionId/matchedRuleId/reason are the original PolicyDecision pinned at
// creation time, not re-derived later - the whole point is that a tenant activating a new
// policy version afterward does not retroactively change what this approval means. See
// ApprovalService for how that's enforced (resolving never calls PolicyDecisionEngine again).
public record Approval(String id, String tenantId, String twinId, String activityId, String twinActivityId,
        Integer loopCounter, String agentType, String action, String policyId, String policyVersionId,
        Integer policyVersionNumber, String matchedRuleId, String reason, ApprovalStatus status, Instant createdAt,
        Instant resolvedAt, String resolution) {

    // convenience for the creation call site, which never has resolvedAt/resolution yet
    public static Approval pending(String id, String tenantId, String twinId, String activityId,
            String twinActivityId, Integer loopCounter, String agentType, String action, String policyId,
            String policyVersionId, Integer policyVersionNumber, String matchedRuleId, String reason,
            Instant createdAt) {
        return new Approval(id, tenantId, twinId, activityId, twinActivityId, loopCounter, agentType, action,
                policyId, policyVersionId, policyVersionNumber, matchedRuleId, reason, ApprovalStatus.PENDING,
                createdAt, null, null);
    }

    public Approval withStatus(ApprovalStatus newStatus, Instant resolvedAt, String resolution) {
        return new Approval(id, tenantId, twinId, activityId, twinActivityId, loopCounter, agentType, action,
                policyId, policyVersionId, policyVersionNumber, matchedRuleId, reason, newStatus, createdAt,
                resolvedAt, resolution);
    }
}
