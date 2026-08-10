package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateRequest {
    private String tenantId;
    private String action;
    private Map<String, Object> attributes;
}
