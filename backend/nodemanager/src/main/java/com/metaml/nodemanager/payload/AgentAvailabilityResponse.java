package com.metaml.nodemanager.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAvailabilityResponse {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
}
