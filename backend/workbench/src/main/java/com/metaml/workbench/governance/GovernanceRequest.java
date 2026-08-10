package com.metaml.workbench.governance;

import java.util.Map;

// Phase 2: the minimum context a rule can actually reference. tenantId picks which tenant's
// policy applies. action covers the common case of a rule like "action == DELETE_CUSTOMER".
// Everything else a rule might check (payment.amount, order.amount, ...) is an arbitrary
// dotted field name, so it lives in attributes rather than as named fields we'd have to keep
// adding to forever. Fields like twinId/agentId/modelId from the Phase 0 diagram are left out
// on purpose - no rule in this phase needs them, and they can go in attributes too once one
// does.
public record GovernanceRequest(String tenantId, String action, Map<String, Object> attributes) {
    public GovernanceRequest {
        attributes = attributes == null ? Map.of() : attributes;
    }
}
