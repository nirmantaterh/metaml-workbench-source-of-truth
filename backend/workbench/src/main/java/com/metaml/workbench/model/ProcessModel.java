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
    // Task/process metadata (name, description, MetaML data items) lives in here as real BPMN
    // documentation and metaml-namespace extension elements, not a separate field -- see
    // frontend/src/components/bpmn/bpmnUtils.js for how it's read and written.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
}
