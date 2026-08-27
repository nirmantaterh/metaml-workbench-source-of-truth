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
    // Optional caller-supplied tenant identifier for multi-tenant scoping.
    private String tenantId;
    private Long projectId;
}
