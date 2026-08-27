package com.metaml.workbench.workflow;

import java.time.Instant;

// Current resolved status and metadata for a workflow stage.
public record StageInfo(StageStatus status, Instant timestamp, String detail, StageError error) {

    static final StageInfo PENDING = new StageInfo(StageStatus.PENDING, null, null, null);

    // Convenience constructor for stage information without structured error metadata.
    public StageInfo(StageStatus status, Instant timestamp, String detail) {
        this(status, timestamp, detail, null);
    }
}
