package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpStatus.*;

import java.util.NoSuchElementException;

import com.metaml.workbench.governance.GovernanceRequest;
import com.metaml.workbench.governance.Policy;
import com.metaml.workbench.governance.PolicyDecision;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.governance.PolicyEvaluationException;
import com.metaml.workbench.governance.PolicyVersion;
import com.metaml.workbench.governance.Tenant;
import com.metaml.workbench.governance.TenantPolicyService;
import com.metaml.wbapi.payload.request.AddPolicyRuleRequest;
import com.metaml.wbapi.payload.request.CreatePolicyRequest;
import com.metaml.wbapi.payload.request.CreateTenantRequest;
import com.metaml.wbapi.payload.request.EvaluateRequest;
import com.metaml.wbapi.payload.request.TenantScopedRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;
import com.metaml.wbapi.utils.FeedbackMessage;

// Manages tenant identities, versioned governance policies, and policy evaluation rules.
@RestController
@RequestMapping(WorkbenchUrlMapping.GOVERNANCE)
@RequiredArgsConstructor
public class TenantPolicyController {

    private final TenantPolicyService tenantPolicyService;
    private final PolicyDecisionEngine policyDecisionEngine;

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_TENANTS)
    public ResponseEntity<ApiResponse> createTenant(@RequestBody CreateTenantRequest request) {
        try {
            Tenant tenant = tenantPolicyService.createTenant(request.getName());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, tenant));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_TENANTS)
    public ResponseEntity<ApiResponse> listTenants() {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, tenantPolicyService.listTenants()));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_TENANTS + "/{tenantId}" + WorkbenchUrlMapping.GOVERNANCE_POLICIES)
    public ResponseEntity<ApiResponse> createTenantPolicy(@PathVariable String tenantId,
            @RequestBody CreatePolicyRequest request) {
        try {
            Policy policy = tenantPolicyService.createTenantPolicy(tenantId, request.getName());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, policy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_TENANTS + "/{tenantId}" + WorkbenchUrlMapping.GOVERNANCE_POLICIES)
    public ResponseEntity<ApiResponse> listTenantPolicies(@PathVariable String tenantId) {
        try {
            return ResponseEntity.ok(
                    new ApiResponse(FeedbackMessage.SUCCESS, tenantPolicyService.listTenantPolicies(tenantId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_PLATFORM_POLICIES)
    public ResponseEntity<ApiResponse> createPlatformPolicy(@RequestBody CreatePolicyRequest request) {
        try {
            Policy policy = tenantPolicyService.createPlatformPolicy(request.getName());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, policy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_PLATFORM_POLICIES)
    public ResponseEntity<ApiResponse> listPlatformPolicies() {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, tenantPolicyService.listPlatformPolicies()));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_POLICIES + "/{policyId}" + WorkbenchUrlMapping.GOVERNANCE_POLICY_VERSIONS)
    public ResponseEntity<ApiResponse> createDraftVersion(@PathVariable String policyId,
            @RequestBody TenantScopedRequest request) {
        try {
            PolicyVersion version = tenantPolicyService.createDraftVersion(policyId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, version));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GOVERNANCE_POLICIES + "/{policyId}" + WorkbenchUrlMapping.GOVERNANCE_POLICY_VERSIONS)
    public ResponseEntity<ApiResponse> listVersions(@PathVariable String policyId,
            @RequestParam(required = false) String tenantId) {
        try {
            return ResponseEntity.ok(
                    new ApiResponse(FeedbackMessage.SUCCESS, tenantPolicyService.listVersions(policyId, tenantId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_POLICY_VERSIONS + "/{versionId}/rules")
    public ResponseEntity<ApiResponse> addRule(@PathVariable String versionId,
            @RequestBody AddPolicyRuleRequest request) {
        try {
            PolicyEffect effect = PolicyEffect.valueOf(request.getEffect());
            PolicyVersion version = tenantPolicyService.addRule(versionId, request.getTenantId(),
                    request.getField(), request.getOperator(), request.getValue(), effect);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, version));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_POLICY_VERSIONS + "/{versionId}/activate")
    public ResponseEntity<ApiResponse> activateVersion(@PathVariable String versionId,
            @RequestBody TenantScopedRequest request) {
        try {
            PolicyVersion version = tenantPolicyService.activateVersion(versionId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, version));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Evaluates a governance request against active policies.
    @PostMapping(WorkbenchUrlMapping.GOVERNANCE_EVALUATE)
    public ResponseEntity<ApiResponse> evaluate(@RequestBody EvaluateRequest request) {
        try {
            PolicyDecision decision = policyDecisionEngine.evaluate(
                    new GovernanceRequest(request.getTenantId(), request.getAction(), request.getAttributes()));
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (PolicyEvaluationException e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }
}
