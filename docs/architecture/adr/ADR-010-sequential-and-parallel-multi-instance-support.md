# ADR-010: Multi-Instance Activities Are Wrapped in an Embedded Sub-Process, Both Sequential and Parallel

**Status:** Accepted (Version 1.0)

## Context

The Original can contain a multi-instance User Task (sequential or parallel, e.g. a committee-review step visited more than once). Preserving that semantic in the Twin, without introducing a different execution model than the rest of the generator uses, required deciding how a "visit N times" characteristic can span the Receive Task + Service Task pair (ADR-005) that every other activity becomes.

## Decision

A multi-instance User Task becomes an **embedded sub-process** wrapping the same `[Receive Task, Service Task]` pair, with Camunda's own `multiInstanceLoopCharacteristics` attached to the sub-process itself (`.sequential()` or `.parallel()` as the Original specifies), restricted to a **literal** loop cardinality. A non-literal (variable or collection-expression) cardinality falls back to a single Twin visit, logged, rather than failing generation — the Twin simply has no variable to evaluate such an expression against.

## Alternatives Investigated

- **Attaching multi-instance characteristics directly to a single flow node spanning both Receive and Service** — not possible: Camunda's multi-instance characteristics attach to exactly one activity, and there is no standard way to make "visit N times" span two sequential nodes without a sub-process scope around them. This is a structural fact about the BPMN spec/Camunda's model, not a design preference.
- **Carrying over non-literal (expression/collection) cardinality by evaluating it against Original process variables** — investigated and rejected: the Twin process instance has no such variable (it is a separate process instance with its own variable scope), so the expression would either fail to evaluate or evaluate against the wrong data. A single-visit fallback, clearly logged, was judged safer than guessing.
- **Rejecting multi-instance activities from generation entirely** (fail-fast, matching the general unsupported-construct policy, [ADR-011](ADR-011-unsupported-bpmn-construct-policy.md)) — not chosen for the literal-cardinality case, since it is fully supportable and was made to work; reserved instead for the genuinely non-literal case only as a graceful degradation, not a hard failure, because losing repeat visits is a smaller functional loss than losing the whole activity.

## Evidence

Proven with a dedicated probe before being relied upon: two correlated visits of a sequential multi-instance activity, asserting the Service Task read `loopCounter` 0 then 1, and that the outer flow only continued once both visits finished — confirming the built-in multi-instance variables remain visible to both wrapped elements. For parallel multi-instance specifically, disambiguation by `loopCounter` (ADR-007) was separately proven with three concurrently-waiting siblings. `stabilizeMultiInstanceIds` was added after a dedicated adversarial review caught that the wrapper's `multiInstanceLoopCharacteristics`/`loopCardinality` elements were, like several other builder-generated elements, non-deterministically id'd across repeated `generate()` calls — verified by generating the same model's Twin twice and diffing the XML before and after the fix.

## Trade-offs

- **Gained:** full sequential and parallel multi-instance fidelity for the common case (literal cardinality), using entirely standard Camunda mechanisms, no custom looping logic.
- **Given up:** non-literal cardinality is a known, accepted, logged degradation rather than a supported feature — judged an acceptable scope boundary rather than something worth the complexity of evaluating arbitrary expressions against a process instance that structurally cannot supply their inputs.

## Consequences

- `LITERAL_CARDINALITY` (a simple digit-only regex) is the exact boundary between "fully supported" and "single-visit fallback" — any BPMN author relying on collection-based multi-instance in the Original should expect the Twin to visit that activity only once, and this is logged clearly enough to be discoverable rather than silently surprising.

## Future Reconsideration

Extending to collection-based cardinality would require deciding what Twin-side collection an expression should evaluate against — a genuinely open design question, not merely an implementation gap, and would warrant its own ADR rather than an incremental patch to this one.
