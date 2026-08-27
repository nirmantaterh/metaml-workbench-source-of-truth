package com.metaml.workbench.workflow;

import java.time.Instant;

// Represents an event recorded for a workflow stage.
public record StageEvent(WorkflowStage stage, StageStatus status, Instant timestamp, String detail,
        StageError error) {

    // Convenience constructor for events without structured error metadata.
    public StageEvent(WorkflowStage stage, StageStatus status, Instant timestamp, String detail) {
        this(stage, status, timestamp, detail, null);
    }
}
