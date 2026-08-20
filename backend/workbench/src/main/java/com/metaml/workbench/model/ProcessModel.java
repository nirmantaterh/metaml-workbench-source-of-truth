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
    // Null for the ordinary single-BPMN path, where a Twin is DERIVED from bpmnXml at generate time
    // (TwinModelGenerator). Set only when the model was saved with its own independently authored
    // second process (e.g. RedCollar's Manuf + Twin) - see
    // WorkbenchService.saveProcessModelWithAuthoredTwin. Not to be confused with TwinProcess/
    // TwinModelGenerator's own "twin" concept (a governance-evolution shadow instance of a running
    // process) - this field is raw BPMN XML for a second, independently modeled process, unrelated
    // to that machinery.
    private String authoredTwinBpmnXml;

    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId,
            String tenantId) {
        this(id, name, bpmnXml, null, createdAt, processDefinitionId, tenantId);
    }

    public ProcessModel(String id, String name, String bpmnXml, String authoredTwinBpmnXml, Instant createdAt,
            String processDefinitionId, String tenantId) {
        this.id = id;
        this.name = name;
        this.bpmnXml = bpmnXml;
        this.authoredTwinBpmnXml = authoredTwinBpmnXml;
        this.createdAt = createdAt;
        this.processDefinitionId = processDefinitionId;
        this.tenantId = tenantId;
    }

    // pre-Phase-1 call sites (including existing tests) construct a ProcessModel with no
    // notion of a tenant - keeps every one of them compiling and behaving exactly as before
    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId) {
        this(id, name, bpmnXml, createdAt, processDefinitionId, null);
    }

    public boolean hasAuthoredTwin() {
        return authoredTwinBpmnXml != null && !authoredTwinBpmnXml.isBlank();
    }
}
