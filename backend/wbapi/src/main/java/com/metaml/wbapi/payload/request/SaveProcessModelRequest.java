package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveProcessModelRequest {
    private String id;
    private String name;
    private String bpmnXml;
    // Tenant ownership (Phase 0 governance audit) - optional. Caller-supplied, not
    // authentication; null behaves exactly like a model saved before tenancy existed.
    private String tenantId;
}
