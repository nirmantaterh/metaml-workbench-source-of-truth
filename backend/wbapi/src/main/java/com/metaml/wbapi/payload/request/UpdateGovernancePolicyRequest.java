package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

// all nullable on purpose - only what's in the body gets changed
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGovernancePolicyRequest {
    private Set<String> deniedAgentTypes;
    private Integer maxEvolutionsPerTwin;
    private Integer maxTwinExecutionsPerTwin;
}
