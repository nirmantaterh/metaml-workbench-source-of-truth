package com.metaml.workbench.workflow;

import java.time.Instant;

// One stage's current, resolved status - computed from the latest event recorded for that stage,
// not stored separately from the event log. timestamp/detail are null when status is PENDING
// (nothing has happened to this stage yet).
public record StageInfo(StageStatus status, Instant timestamp, String detail) {

    static final StageInfo PENDING = new StageInfo(StageStatus.PENDING, null, null);
}
