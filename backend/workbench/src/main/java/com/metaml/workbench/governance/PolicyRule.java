package com.metaml.workbench.governance;

// Immutable rule definition mapping a field condition and operator to a policy effect.
public record PolicyRule(String id, String field, String operator, String value, PolicyEffect effect) {
}
