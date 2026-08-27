package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Base request shape for tenant-scoped governance operations.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantScopedRequest {
    private String tenantId;
}
