package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.http.HttpStatus.*;

import java.util.NoSuchElementException;

import com.metaml.workbench.model.GovernancePolicy;
import com.metaml.workbench.model.GovernanceUsage;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.wbapi.payload.request.UpdateGovernancePolicyRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;
import com.metaml.wbapi.utils.FeedbackMessage;

@RestController
@RequestMapping(WorkbenchUrlMapping.GOVERNANCE)
@RequiredArgsConstructor
public class GovernanceController {
    private final GovernanceService governanceService;
    private final WorkbenchService workbenchService;

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_POLICY)
    public ResponseEntity<ApiResponse> getPolicy() {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, governanceService.getPolicy()));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_POLICY)
    public ResponseEntity<ApiResponse> updatePolicy(@RequestBody UpdateGovernancePolicyRequest request) {
        try {
            GovernancePolicy policy = governanceService.updatePolicy(request.getDeniedAgentTypes(),
                    request.getMaxEvolutionsPerTwin(), request.getMaxTwinExecutionsPerTwin());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, policy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_USAGE + "/{twinProcessId}")
    public ResponseEntity<ApiResponse> getUsage(@PathVariable String twinProcessId) {
        try {
            // governance tracks usage in its own map; validate existence to avoid 0/max for an unlaunched id
            workbenchService.getTwinProcess(twinProcessId);
            GovernanceUsage usage = governanceService.getUsage(twinProcessId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, usage));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }
}
