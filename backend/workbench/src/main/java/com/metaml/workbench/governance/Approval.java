package com.metaml.workbench.governance;

import java.time.Instant;

// Immutable record of a governance approval requirement and execution context.
public record Approval(String id, String tenantId, String twinId, String activityId, String twinActivityId,
        Integer loopCounter, String agentType, String action, String policyId, String policyVersionId,
        Integer policyVersionNumber, String matchedRuleId, String reason, ApprovalStatus status, Instant createdAt,
        Instant resolvedAt, String resolution) {

    // Factory method for creating a pending approval.
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
