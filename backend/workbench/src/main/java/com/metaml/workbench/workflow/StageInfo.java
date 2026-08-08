package com.metaml.workbench.workflow;

import java.time.Instant;

// One stage's current, resolved status - computed from the latest event recorded for that stage,
// not stored separately from the event log. timestamp/detail are null when status is PENDING
// (nothing has happened to this stage yet). error (Phase 3A) is that same latest event's
// structured error info, carried through so a caller can read a failed stage's structured detail
// without re-scanning the raw history - null for anything but a FAILED stage recorded after
// Phase 3A.
public record StageInfo(StageStatus status, Instant timestamp, String detail, StageError error) {

    static final StageInfo PENDING = new StageInfo(StageStatus.PENDING, null, null, null);

    // same reasoning as StageEvent's own overload - keeps every pre-Phase-3A call site compiling
    public StageInfo(StageStatus status, Instant timestamp, String detail) {
        this(status, timestamp, detail, null);
    }
}
