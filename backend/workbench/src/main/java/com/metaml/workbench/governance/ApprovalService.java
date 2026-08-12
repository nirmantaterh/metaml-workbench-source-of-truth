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

// Phase 4: the persisted state machine behind a REQUIRE_APPROVAL decision. Deliberately knows
// nothing about Twins, Camunda, or the node manager - same separation TenantPolicyService keeps
// from BPMN. WorkbenchServiceImpl is the one place that both creates an Approval (when
// PolicyDecisionEngine says REQUIRE_APPROVAL) and actually resumes the twin operation once one
// is approved; this class only owns whether that resumption is allowed to happen, once, and who
// is allowed to ask.
//
// PENDING -> APPROVED -> COMPLETED|FAILED
// PENDING -> REJECTED
// every other transition is rejected, not silently ignored - see markApproved/markRejected.
@Component
public class ApprovalService {

    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final ApprovalStore store;
    // guards every status transition, same demo-scale single-JVM locking TenantPolicyService
    // uses for its own writes - not distributed locking, just enough that two simultaneous
    // approve() calls on the same id can't both win
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

    // same "not found" message whether the id doesn't exist or belongs to another tenant - see
    // TenantPolicyService.requirePolicyAccessible for why that distinction must not leak
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

    // Phase 5 (crash reconciliation): every tenant's APPROVED approvals, not just one. Only the
    // startup reconciliation pass needs this - it runs before any caller has a tenant context to
    // scope by, and an APPROVED approval left over from before a crash could belong to any tenant.
    public List<Approval> listAllApproved() {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : approvals.values()) {
            if (approval.status() == ApprovalStatus.APPROVED) {
                result.add(approval);
            }
        }
        return result;
    }

    // the actual double-execution guard (Phase 4 Step 6/7): only the caller that wins this
    // PENDING->APPROVED transition is allowed to go on and run the twin operation. A second
    // concurrent or later call on the same id finds it already APPROVED (or REJECTED, COMPLETED,
    // FAILED) and is rejected here, before WorkbenchServiceImpl ever touches the twin.
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

    // called only by WorkbenchServiceImpl right after it actually ran the resumed operation, on
    // an id it just itself transitioned to APPROVED - no tenant check needed a second time here,
    // that already happened in markApproved for this same id
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
