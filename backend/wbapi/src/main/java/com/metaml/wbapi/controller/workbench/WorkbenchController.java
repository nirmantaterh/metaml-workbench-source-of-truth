package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.http.HttpStatus.*;

import java.util.NoSuchElementException;

import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.wbapi.payload.request.ConnectActivityRequest;
import com.metaml.wbapi.payload.request.EvolveActivityRequest;
import com.metaml.wbapi.payload.request.LaunchProcessRequest;
import com.metaml.wbapi.payload.request.SaveProcessModelRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;
import com.metaml.wbapi.utils.FeedbackMessage;

@RestController
@RequestMapping(WorkbenchUrlMapping.WORKBENCH)
@RequiredArgsConstructor
public class WorkbenchController {
    private final WorkbenchService workbenchService;

    // public WorkbenchController(WorkbenchService workbenchService) {
    // this.workbenchService = workbenchService;
    // }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_SAMPLE_ONLY)
    public ResponseEntity<ApiResponse> getSampleMethod() {
        try {
            String result = workbenchService.sampleMethod();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, result));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE)
    public ResponseEntity<ApiResponse> saveModel(@RequestBody SaveProcessModelRequest request) {
        try {
            ProcessModel model = workbenchService.saveProcessModel(request.getId(), request.getName(),
                    request.getBpmnXml());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE + "/{id}")
    public ResponseEntity<ApiResponse> getModel(@PathVariable String id) {
        try {
            ProcessModel model = workbenchService.getProcessModel(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_LAUNCH)
    public ResponseEntity<ApiResponse> launchProcess(@RequestBody LaunchProcessRequest request) {
        try {
            TwinProcess twin = workbenchService.launchProcess(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_TWIN + "/{id}")
    public ResponseEntity<ApiResponse> getTwinProcess(@PathVariable String id) {
        try {
            TwinProcess twin = workbenchService.getTwinProcess(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_CONNECT)
    public ResponseEntity<ApiResponse> connectActivity(@RequestBody ConnectActivityRequest request) {
        try {
            TwinProcess twin = workbenchService.connectActivity(request.getTwinProcessId(),
                    request.getOriginalActivityId(), request.getTwinActivityId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE)
    public ResponseEntity<ApiResponse> evolveActivity(@RequestBody EvolveActivityRequest request) {
        try {
            AgentDecision decision = workbenchService.evolveActivity(request.getTwinProcessId(),
                    request.getActivityId(), request.getAgentType());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Pragmatic stand-in for a real Camunda execution listener on the linked activity: rather
    // than attaching a listener to arbitrary user-authored BPMN at deploy time, this reads
    // Camunda's own history for the twin's original process instance and, if the given
    // activity has actually been reached there, forwards it through the same evolve path
    // /evolve uses. Safe to call more than once for the same activity -- the idempotency
    // marker on the twin itself (TwinProcess.forwardedBridgeActivities) is what makes a repeat
    // call a no-op. Governance's quota would NOT prevent that: it caps how many evolutions a
    // twin gets in total, so duplicate forwards would simply burn the twin's budget.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_BRIDGE + "/{twinId}/{activityId}")
    public ResponseEntity<ApiResponse> bridgeActivityEvent(@PathVariable String twinId,
            @PathVariable String activityId) {
        try {
            AgentDecision decision = workbenchService.bridgeActivityEvent(twinId, activityId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }
}
