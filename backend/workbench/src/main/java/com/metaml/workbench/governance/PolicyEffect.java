package com.metaml.workbench.governance;

// What a rule says should happen when it matches. Phase 1 only stores this - nothing
// evaluates a rule against a real request yet. That is the Policy Decision Engine, a later
// phase, once there is a real call site ready to consume a decision (see the Phase 0 audit's
// own notes on where that call site should live).
public enum PolicyEffect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
}
