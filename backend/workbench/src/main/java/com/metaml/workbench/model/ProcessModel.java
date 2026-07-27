package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessModel {
    private String id;
    private String name;
    // description + data items live inside this XML (bpmn:documentation and metaml extension
    // elements), not as fields here. frontend bpmnUtils.js is the other half of that.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
}
