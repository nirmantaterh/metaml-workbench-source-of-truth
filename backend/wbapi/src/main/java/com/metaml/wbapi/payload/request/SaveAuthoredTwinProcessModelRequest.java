package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request payload for saving process models with an independently authored twin.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveAuthoredTwinProcessModelRequest {
    private String id;
    private String name;
    private String bpmnXml;
    private String twinBpmnXml;
    private String tenantId;
    private Long projectId;
}
