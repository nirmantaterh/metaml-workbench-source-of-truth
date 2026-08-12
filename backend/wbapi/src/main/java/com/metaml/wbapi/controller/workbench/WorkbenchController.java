package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.http.HttpStatus.*;

import java.util.List;
import java.util.NoSuchElementException;

import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.wbapi.payload.request.ConnectActivityRequest;
import com.metaml.wbapi.payload.request.EvolveActivityRequest;
import com.metaml.wbapi.payload.request.GenerateDelegatesRequest;
import com.metaml.wbapi.payload.request.GenerateProjectRequest;
import com.metaml.wbapi.payload.request.LaunchProcessRequest;
import com.metaml.wbapi.payload.request.LaunchProjectRequest;
import com.metaml.wbapi.payload.request.SaveProcessModelRequest;
import com.metaml.wbapi.payload.request.StopProjectRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.payload.response.GeneratedProjectResponse;
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
                    request.getBpmnXml(), request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // New scope item 1 (Navigation & UI): backs "Edit Existing Project" - a picker needs something
    // to list, not just a lookup by an id the user already has to know
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE)
    public ResponseEntity<ApiResponse> listModels() {
        try {
            List<ProcessModel> models = workbenchService.listProcessModels();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, models));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Single source of truth for the Model -> Generate -> Launch breadcrumb - never 404s for a
    // model with no history recorded (a model id that's never been through the pipeline just
    // reads back as everything PENDING, which is the honest answer, not an error).
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_WORKFLOW)
    public ResponseEntity<ApiResponse> getWorkflowState(@PathVariable String id) {
        try {
            WorkflowState state = workbenchService.getWorkflowState(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, state));
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

    // Authoring/catalog deletion - removes the model, its .bpmn artifact and its generated
    // projects. Twins, Camunda runtime state, approvals and workflow history are all deliberately
    // retained (see WorkbenchService.deleteProcessModel for why).
    //
    // 409 rather than 400 when a generated application is still running: the request is
    // well-formed and would be valid once the app is stopped, which is exactly what CONFLICT
    // means - the same mapping the approval endpoints below already use for IllegalStateException.
    @DeleteMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE + "/{id}")
    public ResponseEntity<ApiResponse> deleteModel(@PathVariable String id) {
        try {
            workbenchService.deleteProcessModel(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
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

    // New scope item 4 (Spring Boot Generation): the second step of Model -> Generate -> Launch.
    // Assembles a full standalone Spring Boot project (Joanna's camundademo template, with the
    // real BPMN and generated delegates dropped in) and writes it to disk. Doesn't launch it -
    // that's still a separate step, not built yet.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_GENERATE_PROJECT)
    public ResponseEntity<ApiResponse> generateSpringBootProject(@RequestBody GenerateProjectRequest request) {
        try {
            GeneratedProject project = workbenchService.generateSpringBootProject(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, GeneratedProjectResponse.from(project)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Last step of Model -> Generate -> Launch: starts a previously generated project as its own
    // background process on an auto-assigned port. Not to be confused with launchProcess below,
    // which starts a twin PROCESS INSTANCE on the already-running engine - this starts a whole
    // separate Spring Boot app.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_LAUNCH_PROJECT)
    public ResponseEntity<ApiResponse> launchGeneratedProject(@RequestBody LaunchProjectRequest request) {
        try {
            LaunchedProject launched = workbenchService.launchGeneratedProject(request.getProjectId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, launched));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // The counterpart to launch-project. A generated app is a child JVM that outlives the request
    // that started it, so without this the only ways to get its port back were relaunching the same
    // project or killing the workbench.
    //
    // 404 rather than a 200 carrying false, to match how every other "you named something that
    // isn't there" case in this controller answers - the service returns a plain boolean because
    // stopping an already-stopped project is a harmless no-op internally, and this is the layer
    // that decides the caller asked about something that doesn't exist. The body still carries the
    // wasRunning flag either way so a client doesn't have to infer it from the status code alone.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_STOP_PROJECT)
    public ResponseEntity<ApiResponse> stopGeneratedProject(@RequestBody StopProjectRequest request) {
        try {
            boolean wasRunning = workbenchService.stopGeneratedProject(request.getProjectId());
            if (!wasRunning) {
                return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(
                        "No running generated project with id " + request.getProjectId(), false));
            }
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // The Evolve workflow's own future "connect to an existing deployed application" step reads
    // from this - what's actually running right now, not what was ever generated.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_RUNNING_PROJECTS)
    public ResponseEntity<ApiResponse> listRunningProjects() {
        try {
            List<LaunchedProject> running = workbenchService.listRunningProjects();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, running));
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

    // Phase 4 (approval workflow). Same tenant-scoped "not found" convention as the rest of
    // governance - a wrong tenantId gets 404, not 403, so it can't tell an approval that doesn't
    // exist apart from one that belongs to someone else.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS)
    public ResponseEntity<ApiResponse> listApprovals(@org.springframework.web.bind.annotation.RequestParam String tenantId) {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, workbenchService.listApprovals(tenantId)));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS + "/{approvalId}/approve")
    public ResponseEntity<ApiResponse> approveEvolution(@PathVariable String approvalId,
            @RequestBody com.metaml.wbapi.payload.request.ResolveApprovalRequest request) {
        try {
            AgentDecision decision = workbenchService.approveEvolution(approvalId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS + "/{approvalId}/reject")
    public ResponseEntity<ApiResponse> rejectApproval(@PathVariable String approvalId,
            @RequestBody com.metaml.wbapi.payload.request.ResolveApprovalRequest request) {
        try {
            AgentDecision decision = workbenchService.rejectApproval(approvalId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
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
