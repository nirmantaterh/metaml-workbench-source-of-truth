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
    // metadata (description, data items) lives inside the XML as bpmn:documentation +
    // metaml extension elements, not separate fields. See frontend bpmnUtils.js.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
}
