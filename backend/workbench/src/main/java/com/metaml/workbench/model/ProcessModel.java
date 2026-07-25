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
    // Task metadata (assignee/input/output/notes) lives in here as real camunda-namespace
    // extension elements, not a separate field -- see frontend/camundaProperties.js.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
}
