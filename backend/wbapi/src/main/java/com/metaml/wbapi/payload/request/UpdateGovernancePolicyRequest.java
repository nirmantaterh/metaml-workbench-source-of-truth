package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

// Both fields are nullable: only the ones present in the request body are changed, see
// GovernanceServiceImpl.updatePolicy.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGovernancePolicyRequest {
    private Set<String> deniedAgentTypes;
    private Integer maxEvolutionsPerTwin;
}
