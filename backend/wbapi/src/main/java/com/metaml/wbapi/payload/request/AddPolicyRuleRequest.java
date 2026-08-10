package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddPolicyRuleRequest {
    // null tenantId means "this is a platform-level operation"
    private String tenantId;
    private String field;
    private String operator;
    private String value;
    private String effect;
}
