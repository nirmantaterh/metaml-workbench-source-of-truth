package com.metaml.workbench.workflow;

// PENDING isn't ever recorded as an event - it's what a stage reads as when no event exists for
// it yet, so "hasn't started" doesn't need its own row in the log. STOPPED only makes sense for
// LAUNCH (a generated app that finished successfully and was later shut down, distinct from one
// that's still running or one that never launched at all).
public enum StageStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    STOPPED
}
