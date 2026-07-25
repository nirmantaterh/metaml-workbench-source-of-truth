package com.metaml.workbench.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAvailabilityResult {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
}
