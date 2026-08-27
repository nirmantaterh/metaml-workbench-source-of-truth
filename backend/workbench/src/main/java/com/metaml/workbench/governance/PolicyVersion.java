package com.metaml.workbench.governance;

import java.time.Instant;
import java.util.List;

// Immutable record representing a specific version of a governance policy.
public record PolicyVersion(String id, String policyId, int versionNumber, PolicyVersionStatus status,
        List<PolicyRule> rules, Instant createdAt, Instant activatedAt) {
}
