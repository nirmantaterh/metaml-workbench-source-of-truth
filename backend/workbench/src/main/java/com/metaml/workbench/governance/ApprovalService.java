package com.metaml.workbench.governance;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Manages state transitions for governance approval decisions.
@Component
public class ApprovalService {

    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final ApprovalStore store;
    // Synchronizes status transitions across concurrent requests.
    private final Object writeLock = new Object();

    public ApprovalService(ApprovalStore store) {
        this.store = store;
    }

    @PostConstruct
    void restore() {
        for (Approval approval : store.load()) {
            approvals.put(approval.id(), approval);
        }
    }

    public Approval create(String tenantId, String twinId, String activityId, String twinActivityId,
            Integer loopCounter, String agentType, String action, String policyId, String policyVersionId,
            Integer policyVersionNumber, String matchedRuleId, String reason) {
        Approval approval = Approval.pending(UUID.randomUUID().toString(), tenantId, twinId, activityId,
                twinActivityId, loopCounter, agentType, action, policyId, policyVersionId, policyVersionNumber,
                matchedRuleId, reason, Instant.now());
        approvals.put(approval.id(), approval);
        persist();
        return approval;
    }

    // Fetches an approval for a specific tenant.
    public Approval get(String approvalId, String tenantId) {
        Approval approval = approvals.get(approvalId);
        if (approval == null || !Objects.equals(approval.tenantId(), tenantId)) {
            throw new NoSuchElementException("Approval not found: " + approvalId);
        }
        return approval;
    }

    public List<Approval> listForTenant(String tenantId) {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : approvals.values()) {
            if (Objects.equals(approval.tenantId(), tenantId)) {
                result.add(approval);
            }
        }
        return result;
    }

    // Returns all APPROVED approvals across all tenants for startup reconciliation.
    public List<Approval> listAllApproved() {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : approvals.values()) {
            if (approval.status() == ApprovalStatus.APPROVED) {
                result.add(approval);
            }
        }
        return result;
    }

    // Transition status from PENDING to APPROVED.
    public Approval markApproved(String approvalId, String tenantId) {
        synchronized (writeLock) {
            Approval approval = get(approvalId, tenantId);
            if (approval.status() != ApprovalStatus.PENDING) {
                throw new IllegalStateException("Cannot approve " + approvalId + " - it is " + approval.status()
                        + ", not PENDING");
            }
            Approval updated = approval.withStatus(ApprovalStatus.APPROVED, Instant.now(), null);
            approvals.put(approvalId, updated);
            persist();
            return updated;
        }
    }

    public Approval markRejected(String approvalId, String tenantId) {
        synchronized (writeLock) {
            Approval approval = get(approvalId, tenantId);
            if (approval.status() != ApprovalStatus.PENDING) {
                throw new IllegalStateException("Cannot reject " + approvalId + " - it is " + approval.status()
                        + ", not PENDING");
            }
            Approval updated = approval.withStatus(ApprovalStatus.REJECTED, Instant.now(), null);
            approvals.put(approvalId, updated);
            persist();
            return updated;
        }
    }

    // Transition status to COMPLETED after execution.
    public Approval markCompleted(String approvalId, String resolution) {
        return finishExecution(approvalId, ApprovalStatus.COMPLETED, resolution);
    }

    public Approval markFailed(String approvalId, String resolution) {
        return finishExecution(approvalId, ApprovalStatus.FAILED, resolution);
    }

    private Approval finishExecution(String approvalId, ApprovalStatus finalStatus, String resolution) {
        synchronized (writeLock) {
            Approval approval = approvals.get(approvalId);
            if (approval == null) {
                throw new NoSuchElementException("Approval not found: " + approvalId);
            }
            if (approval.status() != ApprovalStatus.APPROVED) {
                throw new IllegalStateException("Cannot finish " + approvalId + " - it is " + approval.status()
                        + ", not APPROVED");
            }
            Approval updated = approval.withStatus(finalStatus, approval.resolvedAt(), resolution);
            approvals.put(approvalId, updated);
            persist();
            return updated;
        }
    }

    private void persist() {
        store.save(new ArrayList<>(approvals.values()));
    }
}
