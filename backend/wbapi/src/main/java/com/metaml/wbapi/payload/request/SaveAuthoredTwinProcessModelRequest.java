package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Same shape as SaveProcessModelRequest, plus the second, independently authored BPMN (e.g. a
// Twin process modeled and supplied separately from the primary/Manufacturing one) - see
// WorkbenchService.saveProcessModelWithAuthoredTwin.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveAuthoredTwinProcessModelRequest {
    private String id;
    private String name;
    private String bpmnXml;
    private String twinBpmnXml;
    private String tenantId;
}
