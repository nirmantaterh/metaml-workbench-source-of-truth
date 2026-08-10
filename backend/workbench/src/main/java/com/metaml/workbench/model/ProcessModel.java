package com.metaml.workbench.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
public class ProcessModel {
    private String id;
    private String name;
    // description + data items live inside this XML (bpmn:documentation and metaml extension
    // elements), not as fields here. frontend bpmnUtils.js is the other half of that.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
    // Phase 1 (tenant identity, see the Phase 0 governance audit): which tenant owns this
    // model. Null for models saved before tenancy existed, or for any model nobody has
    // assigned to a tenant yet - that's "unowned/legacy", not an error, the same way a model
    // with no workflow history just reads as PENDING instead of failing.
    private String tenantId;

    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId,
            String tenantId) {
        this.id = id;
        this.name = name;
        this.bpmnXml = bpmnXml;
        this.createdAt = createdAt;
        this.processDefinitionId = processDefinitionId;
        this.tenantId = tenantId;
    }

    // pre-Phase-1 call sites (including existing tests) construct a ProcessModel with no
    // notion of a tenant - keeps every one of them compiling and behaving exactly as before
    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId) {
        this(id, name, bpmnXml, createdAt, processDefinitionId, null);
    }
}
