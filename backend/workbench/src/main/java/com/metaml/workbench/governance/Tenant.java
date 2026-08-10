package com.metaml.workbench.governance;

import java.time.Instant;

// Phase 1 of the enterprise governance work (see the Phase 0 architecture audit): minimal
// tenant identity - id, name, createdAt. Not an IAM system. See TenantPolicyService's own
// header comment for the trust boundary this does not attempt to solve yet.
public record Tenant(String id, String name, Instant createdAt) {
}
