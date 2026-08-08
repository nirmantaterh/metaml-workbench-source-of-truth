package com.metaml.workbench.workflow;

import java.time.Instant;

// One thing that happened to one stage of one model's pipeline. This is the actual source of
// truth (see WorkflowStateTracker) - detail carries the error message on FAILED, or a small
// human-readable fact on COMPLETED (the generated project id, the port it launched on) so the
// history is useful for debugging on its own, not just a timeline of bare status changes. error
// (Phase 3A) carries the same FAILED information as structured fields instead of free text, when
// the backend actually has it available - see StageError's own comment. Null for every event that
// isn't a failure, and null for FAILED events persisted before Phase 3A existed.
public record StageEvent(WorkflowStage stage, StageStatus status, Instant timestamp, String detail,
        StageError error) {

    // pre-Phase-3A call sites (and there are many, across production code and tests) construct a
    // StageEvent with no notion of structured error info - this keeps every one of them compiling
    // and behaving exactly as before rather than forcing a mechanical `, null` onto each of them
    public StageEvent(WorkflowStage stage, StageStatus status, Instant timestamp, String detail) {
        this(stage, status, timestamp, detail, null);
    }
}
