# ADR-012: Restart Safety Is Achieved by Derivation, Not by Persisting More State

**Status:** Accepted (Version 1.0) — reached only after two incorrect intermediate attempts, both recorded here deliberately

## Context

Phase 7's red-team review (finding W4) identified that the bridge deduplication guard (`forwardedBridgeActivities`, a plain in-memory `Set<String>` on `TwinProcess`) lived only in application memory and was wiped by any ordinary app restart — silently reopening every already-bridged activity visit to a second evolution. The Runtime Architecture's own stated invariant ([ADR-003](ADR-003-shared-h2-runtime-as-source-of-truth.md)) is that Camunda's own runtime state is authoritative; an app-memory-only guard for a fact Camunda's own tables should be able to answer is a direct violation of that invariant.

## Decision

The dedup guard is **fully derived** from Camunda's own history, with **nothing new persisted**:

- For a multi-instance visit (has a `loopCounter`): check whether `evolvedAgent_<twinActivityId>_<loopCounter>` is already set (`runtimeService.getVariable`, falling back to `historyService` for an ended Twin) — the name is already visit-unique by construction.
- For a plain, non-multi-instance activity revisited via an ordinary loop-back gateway (no `loopCounter`, same variable name every visit): compare which numbered visit (by start time) this `activityInstanceId` is, against how many times `evolvedAgent_<twinActivityId>` has actually been **set** — read via `historyService.createHistoricDetailQuery().variableUpdates()`, which records every individual write, not `HistoricVariableInstance`, which only ever holds the current value.

`forwardedBridgeActivities` was deleted entirely from `TwinProcess`, not persisted in a new form — there is nothing left to lose on restart because there is nothing left outside Camunda's own tables.

## Alternatives Investigated

- **Persist `forwardedBridgeActivities` to `WorkbenchStateStore`'s JSON snapshot** — the "obvious" fix, explicitly rejected. It would satisfy "survives restart" but not "shared Camunda runtime is the source of truth"; Phase 7.5's own directive named this the preferred order explicitly: *derive, don't duplicate, and only introduce persistence if derivation is empirically proven impossible.* Derivation was not proven impossible — it took two attempts to get right, but it was never actually impossible.
- **First derivation attempt: check `evolvedAgent_<twinActivityId>[_loopCounter]` variable *existence*** — implemented, tested, and shipped as the first version of this correction. **Wrong**, caught by an independent adversarial review: correct for multi-instance visits (the name is already unique), but for a plain activity revisited via a loop-back gateway, every visit writes the *identical* variable name — visit #2 read as "already forwarded" the instant visit #1 succeeded, reproducing the exact class of bug `forwardedBridgeActivities`'s own original comment had warned about ("one entry per visit, not per activity, or a loop's second time round looks like a duplicate").
- **Second derivation attempt: compare visit ordinal against how many times the Twin's *automation task* has finished** — implemented to fix the loop-back regression above, and it did fix it — but broke the pre-existing incident-retry regression (`TwinAutomationIncidentTest`) the moment it was run alongside it. Root cause: a failed automation rolls its whole Camunda command back, including the automation task's own historic instance, but *not* the evolve step's variable write, which is a separate, already-committed command. Counting automation completions conflated two different questions — "did evolution already succeed" (must never repeat) and "did automation already succeed" (must be retried, that is the entire point of [ADR-008](ADR-008-incident-driven-failure-policy.md)) — and got the wrong answer for exactly the retry case incident-handling exists to protect.
- **Final, shipped derivation: compare visit ordinal against how many times `evolvedAgent_<twinActivityId>` has actually been *set*, via `HistoricDetail.variableUpdates()`** — survives an automation rollback (the write it counts is not part of the automation's own failed command) and correctly distinguishes a genuinely new loop-back visit from a retry of the same one. Verified empirically before shipping: a throwaway probe confirmed setting one variable name twice on a process instance produces two distinct, ordered `HistoricVariableUpdate` rows, not one collapsed value.

## Evidence

Three dedicated regression tests, each proven to fail against the version of the code it was written to catch, all passing together against the final version: `bridgeDedupeIsSafeAcrossACompletelyFreshServiceInstance` (restart simulation via a second `WorkbenchServiceImpl` instance sharing nothing but the Camunda-backed beans), `aPlainActivityRevisitedThroughALoopBackGatewayBridgesEveryVisitNotJustTheFirst` (the loop-back regression that broke the first attempt), and the pre-existing `TwinAutomationIncidentTest` (the incident-retry regression that broke the second attempt, now passing as a cross-check on the third). Full backend suite green, twice consecutively, with all three tests included.

## Trade-offs

- **Gained:** the dedup guard is now correct for every visit shape this system supports (plain, sequential MI, parallel MI, loop-back), restart-safe by construction (nothing to lose), and required *deleting* code rather than adding a new persistence mechanism.
- **Given up:** the final mechanism costs more per bridge call than a `Set.contains()` would have (a `HistoricDetail` query in the loop-back branch specifically) — accepted, since this runs once per activity-start event, not in a hot path, and correctness across two wrong attempts was worth the extra query cost of getting it right.

## Consequences

- This correction's own history is the strongest concrete evidence in the whole project for why "send every fix through an independent adversarial review before calling it done" is a load-bearing practice, not a formality — the shipped, correct version is the *third* attempt, and the first two both passed their own initial test before a fresh reviewer broke them.
- A documented, narrow residual gap remains: two genuinely concurrent tokens re-entering the same plain activity (e.g. an Inclusive Gateway split looping back into it) would break the ordinal-by-start-time assumption this fix relies on. Not exercised by any current model; recorded in Architecture Specification Section 9 rather than silently assumed away.

## Future Reconsideration

Closing the concurrent-loop-back gap noted above would be the natural next increment to this ADR, if a model ever actually needs it — likely requiring the same kind of disambiguation multi-instance already solves ([ADR-007](ADR-007-execution-targeted-messaging.md)) for a case that isn't multi-instance at all.
