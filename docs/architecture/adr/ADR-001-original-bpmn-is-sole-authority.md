# ADR-001: The Original Process Instance Is the Sole Authority, With One Bounded, Declared Exception

**Status:** Accepted (Version 1.0) — corrected in Phase 10 (the original text of this ADR was factually wrong about the codebase's own shipped behavior)

## Context

A digital twin architecture needs an unambiguous answer to "which side is allowed to change reality." Two live process instances that could both independently drive business decisions would make the Original's own outcome depend on race conditions between the two, which defeats the purpose of a twin that is supposed to *mirror*, not *compete with*, the real process.

## Decision

The **Original** process instance is authoritative for all real-world state and progression. It runs the model author's own deployed BPMN definition unmodified, with real human-facing user tasks. Every synchronization event flows one direction only: Original → Twin.

**Correction (Phase 10):** the first version of this ADR additionally claimed "the Twin never writes to the Original, never blocks it, and never gates its progress on anything the Twin does." That claim was false the entire time this document existed — `AgentExecutionDelegate` unconditionally copied a Twin-evolved agent's `riskFlagged` output onto the Original's own `agentFlaggedRisk` variable, which the Original's own gateway (`Flow_Checks_Fail` in `citibank-wire-transfer.bpmn`) reads to route to escalation instead of approval. This was found by Phase 9's independent adversarial review and re-run live against the current engine to confirm it, not merely inferred from the diff. The rule as actually intended, and now actually enforced by code, is narrower: the Twin may influence a *specific, named, model-declared* Original variable, and only when that activity's own `metaml:agentOutputs` declaration explicitly opts into it — the exact same declaration mechanism every other agent output already goes through (`AgentOutputDeclarations`). Nothing about token progression, task assignment, or gateway *structure* is ever touched by the Twin; only the *value* of a variable the model author explicitly asked to receive.

## Alternatives Investigated

- **Bidirectional synchronization with no bound at all** (any Twin automation outcome could influence any Original routing) — rejected outright, both originally and on re-examination; it reintroduces exactly the "which side is real" ambiguity this decision exists to remove.
- **Removing the write-back channel entirely** (Phase 10 candidate, considered specifically to make the original one-way claim true) — rejected: it would break the citibank demo's own credit-risk-escalation path, which is a real, working, intentional capability this project needs to demonstrate, not an accident to undo. "Preserve existing behavior" outweighed "make the original wording literally true by deleting a feature."
- **Leave the write-back unbounded, only fix the documentation** (Phase 10 candidate) — rejected: this would have left ADR-015's separate promise ("zero changes required elsewhere" for a new project) still false, since any future project's automation naming an output `riskFlagged` would silently inherit routing influence over its own Original process with no BPMN-level opt-in. Documentation-only would have been the smaller change but the wrong one; the gap was real, not just poorly described.
- **Gate the write-back through the same declared-outputs mechanism every other output uses** (chosen) — smallest change that makes both the documentation accurate and the capability properly bounded, with zero behavioral change for the one model that already relies on it.
- **Twin as co-authority with conflict resolution** — not seriously investigated; the complexity (some form of consensus or last-writer-wins between two Camunda instances) is disproportionate to any stated goal.

## Evidence

`AgentExecutionDelegate.notify` now computes `riskFlagDeclared = RISK_FLAG_VARIABLE.equals(declared.get(AgentVariables.RISK_FLAGGED_OUTPUT))` before ever treating an output named `riskFlagged` as routing-relevant — the exact same `declared` map (from `AgentOutputDeclarations`, populated from the activity's own `metaml:agentOutputs` extension elements) that already gates every other named output. `citibank-wire-transfer.bpmn`'s `Task_Credit` already declares `riskFlagged` → `agentFlaggedRisk` (confirmed directly: `outputDeclarations.forActivity(twin.getTwinProcessDefinitionId(), CREDIT)).containsEntry("riskFlagged", "agentFlaggedRisk")`, asserted in `TwinExecutionWalkthroughTest`), so this is a zero-behavior-change fix for the shipped demo — proven by the full backend suite passing unchanged, including every existing risk-escalation assertion. A new regression test, `AgentOutputWalkthroughTest.anUndeclaredRiskFlaggedOutputNeverReachesTheLegacyVariable`, proves the gap is actually closed: an activity that never declares `riskFlagged` gets the generic, always-safe `agentOutput_<activityId>_riskFlagged` name but never the bare `agentFlaggedRisk` variable, confirmed to fail against the pre-fix code before the fix and pass after.

## Trade-offs

- **Gained:** the stated invariant and the shipped behavior now actually match; a future project cannot accidentally inherit routing influence over its own Original process just by naming an output `riskFlagged`; the one legitimate use of this channel (the citibank demo) is completely unaffected.
- **Given up:** nothing measurable — this closes a real gap at zero cost to existing behavior, which is why it was chosen over every other candidate.

## Consequences

- The Original's own escalation logic (e.g. a risk-flagged agent sending it down a compliance branch) can diverge from the Twin's own default-branch behavior when the Twin was evolved differently (Section 7 of the Architecture Specification) — an accepted, documented consequence of one-way *control* authority, unaffected by this correction, which is only about a bounded *data* channel.
- Any future project wanting a Twin-to-Original variable influence channel of its own gets it automatically and safely, the moment its BPMN declares the mapping via `metaml:agentOutputs` — no code change required, and no risk of an undeclared output silently acquiring the same power.
- This documentation itself was wrong for as long as it existed prior to this correction — a direct, concrete instance of why Phase 9's own rule ("do not trust documentation, trust only source code and empirical investigation") is not a formality. The first draft of this ADR was written confidently, in the same session as the code it was describing, and was still wrong.

## Future Reconsideration

Would be revisited only if a genuinely new requirement emerged for the Twin to influence the Original beyond a declared variable value — token progression, task assignment, or gateway structure itself — which is not anticipated, not designed for, and would need its own ADR rather than being retrofitted here.
