package com.metaml.workbench.workflow;

import java.time.Instant;

// One thing that happened to one stage of one model's pipeline. This is the actual source of
// truth (see WorkflowStateTracker) - detail carries the error message on FAILED, or a small
// human-readable fact on COMPLETED (the generated project id, the port it launched on) so the
// history is useful for debugging on its own, not just a timeline of bare status changes.
public record StageEvent(WorkflowStage stage, StageStatus status, Instant timestamp, String detail) {
}
