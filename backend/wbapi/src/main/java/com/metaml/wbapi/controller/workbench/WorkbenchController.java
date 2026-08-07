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

import java.util.List;
import java.util.NoSuchElementException;

import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.wbapi.payload.request.ConnectActivityRequest;
import com.metaml.wbapi.payload.request.EvolveActivityRequest;
import com.metaml.wbapi.payload.request.GenerateDelegatesRequest;
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // New scope item 3 (BPMN Processing): the first step of Model -> Generate -> Launch. Returns
    // one generated Java Delegate class per unique delegateExpression on the saved model's service
    // tasks - read-only, nothing is written to disk or deployed yet. That's the Spring Boot
    // generation step, still waiting on the template project's exact controller shape.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_GENERATE)
    public ResponseEntity<ApiResponse> generateDelegates(@RequestBody GenerateDelegatesRequest request) {
        try {
            List<GeneratedDelegate> delegates = workbenchService.generateDelegates(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, delegates));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
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

    // Manual bridge. AutoBridgeTrigger handles this on its own now, but it's still the only way
    // to bridge an activity you connected after the original already walked past it - there's no
    // second start event coming. Repeat calls on the same visit are a no-op, including a click
    // on something the auto-bridge already picked up.
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

    // moves the ORIGINAL along so the next activity can be evolved/bridged. everything open,
    // not one task - parallel gateway leaves several
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_COMPLETE_TASK + "/{twinId}")
    public ResponseEntity<ApiResponse> completeCurrentTasks(@PathVariable String twinId) {
        try {
            List<String> completed = workbenchService.completeCurrentTasks(twinId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, completed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }
}
