package com.metaml.workbench.workflow;

// Structured detail for a FAILED StageEvent, alongside (not replacing) the free-form `detail` string every StageEvent already carries - `detail` stays the human-readable message, this is the same failure broken into fields something other than a person can read. modelId/stage/ timestamp aren't repeated here since StageEvent and the WorkflowState around it already carry those; duplicating them here would just be a second place they could drift out of sync. Every field is optional and genuinely nullable - populated only when the catch site that recorded the failure actually had that information to hand (see WorkbenchServiceImpl's three FAILED call sites). Nothing here is guessed, parsed out of a message, or fabricated: in particular, delegateExpression/bpmnElementId are null everywhere right now because nothing in the current generate/launch failure paths can actually attribute a failure to one specific BPMN element (see this class's own Phase 3A report for why).
public record StageError(String errorType, String operation, String projectId, Integer port,
        Integer exitCode, String delegateExpression, String bpmnElementId) {
}
