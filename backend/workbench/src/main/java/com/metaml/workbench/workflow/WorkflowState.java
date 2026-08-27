package com.metaml.workbench.workflow;

import java.util.List;
import java.util.Map;

// The whole answer to "where is this model's pipeline right now" - currentStage is which stage the breadcrumb should highlight as active, stages is every stage's resolved status (for rendering done/failed/pending), and history is the raw event log underneath both, for anyone who needs the actual sequence of what happened rather than just the current snapshot.
public record WorkflowState(String modelId, WorkflowStage currentStage, Map<WorkflowStage, StageInfo> stages,
        List<StageEvent> history) {
}
