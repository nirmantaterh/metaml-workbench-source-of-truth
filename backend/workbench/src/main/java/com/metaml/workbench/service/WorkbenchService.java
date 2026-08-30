package com.metaml.workbench.service;

import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;

import java.util.List;

public interface WorkbenchService {

    String sampleMethod();

    // Default overload saving an unowned process model.
    default ProcessModel saveProcessModel(String id, String name, String bpmnXml) {
        return saveProcessModel(id, name, bpmnXml, null);
    }

    // Saves a process model with tenant ownership metadata.
    ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId);

    // The Project UI uses this overload so a saved process belongs to the project the user chose.
    default ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId, Long projectId) {
        return saveProcessModel(id, name, bpmnXml, tenantId);
    }

    // Saves a process model with an independently authored twin BPMN.
    ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId);

    default ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        return saveProcessModelWithAuthoredTwin(id, name, bpmnXml, twinBpmnXml, tenantId);
    }

    ProcessModel getProcessModel(String id);

    // Deletes a process model, its BPMN artifact, and generated projects.
    boolean deleteProcessModel(String modelId);

    // Non-mutating preflight used by project deletion, so a project is never partly deleted.
    boolean canDeleteProcessModel(String modelId);

    // Lists all saved process models, newest first.
    List<ProcessModel> listProcessModels();

    // Lists process model summaries including project ownership.
    List<ProcessModelSummaryDto> listProcessModelSummaries();

    // Generates Java Delegate source classes for service tasks.
    List<GeneratedDelegate> generateDelegates(String modelId);

    // Assembles and generates a standalone Spring Boot project on disk.
    GeneratedProject generateSpringBootProject(String modelId);

    // Launches a generated project as a background process.
    LaunchedProject launchGeneratedProject(String projectId);

    // Stops a running generated project process.
    boolean stopGeneratedProject(String projectId);

    // Lists all currently running generated Target Platform projects.
    List<LaunchedProject> listRunningProjects();

    // Computes workflow state for the Model -> Generate -> Launch pipeline.
    WorkflowState getWorkflowState(String modelId);

    // Launches both original and twin process instances.
    TwinProcess launchProcess(String modelId);

    TwinProcess getTwinProcess(String id);

    // Lists all twin processes belonging to the specified model identifier.
    List<TwinProcess> listTwinProcesses(String modelId);

    // Resolves TwinProcess by ID without re-executing status queries.
    TwinProcess findTwinProcess(String id);

    TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    // Approves a pending evolution decision.
    AgentDecision approveEvolution(String approvalId, String tenantId);

    AgentDecision rejectApproval(String approvalId, String tenantId);

    // Lists governance approvals for a given tenant.
    List<Approval> listApprovals(String tenantId);

    // manual Bridge button: works out which visit of the activity the original is on
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId);

    // Bridges an activity event for a specific visit instance.
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId);

    // Advances a twin activity by correlating its receive message.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId);

    // Advances a parallel multi-instance twin activity.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId, String originalExecutionId);

    // Completes all open user tasks on the original process instance.
    List<String> completeCurrentTasks(String twinProcessId);

    // Records agent execution event on twin process log.
    void recordAgentExecution(String twinProcessId, String variableName, Object agentName);

    // Lists available candidate agents from the authoritative Node Manager catalog.
    List<com.metaml.workbench.client.AgentAvailabilityResult> listAvailableAgents();
}
