package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDecision {
    private String agentType;
    private boolean approved;
    private String agentName;
    private String reason;
}
