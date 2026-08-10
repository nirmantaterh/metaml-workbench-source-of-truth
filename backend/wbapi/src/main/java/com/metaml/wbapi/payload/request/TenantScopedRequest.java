package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// null tenantId means "this is a platform-level operation" - see TenantPolicyService's own
// header comment on why that is the one flag distinguishing platform from tenant, not a
// separate request shape.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantScopedRequest {
    private String tenantId;
}
