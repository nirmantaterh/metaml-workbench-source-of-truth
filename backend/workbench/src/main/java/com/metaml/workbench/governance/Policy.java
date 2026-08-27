package com.metaml.workbench.governance;

import java.time.Instant;

// Policy definition record, representing either a platform policy (null tenantId) or tenant policy.
public record Policy(String id, String tenantId, String name, Instant createdAt) {

    public boolean isPlatform() {
        return tenantId == null;
    }
}
