package com.metaml.workbench.governance;

// Deliberately flat - field/operator/value, not a DSL. "order.amount > 5000" is stored as
// field="order.amount", operator=">", value="5000". Nothing evaluates this yet; Phase 1 is
// only the data model needed to store a rule, per the Phase 0 audit's own instruction not to
// invent a condition engine this early.
public record PolicyRule(String id, String field, String operator, String value, PolicyEffect effect) {
}
