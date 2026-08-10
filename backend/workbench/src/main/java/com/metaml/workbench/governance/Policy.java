package com.metaml.workbench.governance;

import java.time.Instant;

// tenantId is null for a platform policy (MetaML-owned, not any one tenant's) and never null
// for a tenant policy. This one field is what distinguishes "platform" from "tenant" - a
// separate class for platform policies would just be this same shape with an always-null
// field, not a real distinction worth its own type.
public record Policy(String id, String tenantId, String name, Instant createdAt) {

    public boolean isPlatform() {
        return tenantId == null;
    }
}
