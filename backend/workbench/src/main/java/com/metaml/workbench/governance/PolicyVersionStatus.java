package com.metaml.workbench.governance;

// Smallest useful lifecycle for a policy version: draft while it's being built, active once
// published (immutable from that point on), retired once superseded by a newer active version.
// No separate "publish" state - activating IS the publish event, a fourth state here would be
// complexity without a named use case.
public enum PolicyVersionStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
