package com.metaml.workbench.governance;

import java.time.Instant;
import java.util.List;

// Immutable once status is ACTIVE. Rules can only be added while a version is DRAFT - see
// TenantPolicyService.addRule. Editing an already-active policy creates a new draft version
// instead of mutating this one; activatedAt is null until the version is actually activated.
public record PolicyVersion(String id, String policyId, int versionNumber, PolicyVersionStatus status,
        List<PolicyRule> rules, Instant createdAt, Instant activatedAt) {
}
