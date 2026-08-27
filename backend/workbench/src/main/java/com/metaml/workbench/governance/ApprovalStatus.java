package com.metaml.workbench.governance;

// Phase 4: PENDING -> APPROVED|REJECTED. APPROVED -> COMPLETED|FAILED. No separate EXECUTING checkpoint - execution happens synchronously inside the same call that marks APPROVED, and nothing here auto-retries on restart regardless of which of the two a crash landed on, so a third persisted state would carry no real recovery behavior, just more states to reason about. REJECTED/COMPLETED/FAILED are all terminal - none of them accept another approve() or reject() call.
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}
