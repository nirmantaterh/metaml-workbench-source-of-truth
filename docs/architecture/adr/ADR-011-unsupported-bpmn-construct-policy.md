# ADR-011: Unsupported BPMN Constructs Fail Twin Generation Explicitly

**Status:** Accepted (Version 1.0) — corrected in Phase 7.5 (previously violated this ADR's own principle)

## Context

`TwinModelGenerator` only knows how to transform a specific set of BPMN constructs (Architecture Specification, Section 5). The question is what should happen when the Original contains something outside that set: silently drop it and continue, or refuse to build a Twin at all.

## Decision

Any node the generator cannot transform now **fails Twin generation** with an `IllegalArgumentException` naming the process, the specific activity id, and its BPMN element type — *except* Boundary Events, which remain a deliberate, separately-justified silent drop (Architecture Specification, Section 5: a Boundary Timer on a Twin activity that now genuinely finishes inside one job would fire on the Twin's own clock, causing exactly the divergence this whole architecture exists to prevent).

## Alternatives Investigated

- **Silent warn-and-drop for everything unsupported** — this was the *actual, shipped* Phase-7 behavior, and Phase 7's own red-team review (finding W2) identified it as a direct violation of "the Twin generator must never silently generate an incomplete Twin." A `logger.warn` and a dropped node (plus everything only reachable through it) is exactly that failure mode with the volume turned down — a developer could deploy a Twin missing entire branches and have no signal beyond a log line nobody was watching.
- **Attempting best-effort support for every construct** (e.g. treating a Call Activity as a no-op pass-through) — rejected; a fabricated, semantically-wrong transformation is worse than an honest failure, since it would silently misrepresent what the Twin actually does.
- **Failing generation for Boundary Events too, for consistency** — considered and rejected specifically because Boundary Events are not an *unrecognized* construct in the same sense as, say, a Call Activity; the generator fully understands what a Boundary Timer means and has a specific, reasoned decision (not an implementation gap) about why it must not carry over. Conflating "we chose not to" with "we don't know how" would make the fail-fast diagnostic misleading.

## Evidence

Traced the full `launchProcess → deployTwinDefinition → generate()` call chain to confirm the new throw always fires *before* any Camunda deployment or app-side persistence occurs — a rejected model leaves no partial deployment, no orphaned `ProcessModel`, nothing to clean up. Both shipped example models (`citibank-wire-transfer.bpmn`, `grad-admission-review.bpmn`) were checked construct-by-construct and confirmed to use nothing outside the fully-supported set, so this change is safe against every existing walkthrough. `generatingATwinFailsFastOnAnUnsupportedConstructInsteadOfSilentlyDroppingIt` (`TwinExecutionWalkthroughTest`) is the dedicated regression, proven to fail against the pre-Phase-7.5 code before the fix, per the standing empirical-verification standard.

## Trade-offs

- **Gained:** a developer building a process model for this system gets an immediate, precise, actionable error the moment they use an unsupported construct, instead of discovering a silently-truncated Twin during a demo.
- **Given up:** a model that happens to use one unsupported construct in a branch the demo never exercises can no longer be launched at all, even though the missing piece might never matter in practice. Accepted as strictly preferable to the alternative of an operator not knowing what's missing.

## Consequences

- Every future extension to `isSupported()`/`append()` (adding real support for, say, Event-Based Gateway) removes one entry from the fail-fast set — this ADR's list of currently-unsupported constructs (Architecture Specification, Section 5 / Section 9) should be treated as a living checklist, not a permanent boundary.
- The `AdHocSubProcess` case is different in kind from the rest of this list: it is not something the generator *chooses* not to support, it is something the bundled `camunda-bpmn-model` 7.22.0 library cannot represent at all (confirmed via `javap`), and should not be conflated with an ordinary Implementation Gap when reasoning about future work.

## Future Reconsideration

Each entry in the current "Implementation Gap" list (Event-Based Gateway, Call Activity, sub-processes, pre-existing automated task types) is independently reconsiderable as its own future decision to actually implement support, following the same "derive using standard Camunda mechanisms first" discipline this whole document tries to model.
