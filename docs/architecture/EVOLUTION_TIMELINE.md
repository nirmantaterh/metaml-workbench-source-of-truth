# Architecture Evolution Timeline

A chronological record of how the Digital Twin Runtime reached Version 1.0 — what was assumed, what turned out to be wrong, what Camunda actually does versus what seemed reasonable to expect, and every pivot, rejected alternative, bug, and review along the way. Each entry is tagged with its category. Full narrative detail (including verbatim probe descriptions) lives in [PROF_QA_PREP.md](../../PROF_QA_PREP.md); this document is the structured, chronological summary.

**Legend:** 🎯 Original Assumption · ❌ Assumption Later Disproven · 🔍 Camunda Discovery · 🔀 Architectural Pivot · 🚫 Rejected Alternative · 🐛 Significant Bug · 🧪 Empirical Investigation · 👁 Adversarial Review · ✅ Correction · 🏁 Convergence

---

## Stage 1 — The Passive Twin

- 🎯 **Original assumption:** a "digital twin" could be a second Camunda process instance running the *same* deployed definition as the Original, used to observe and annotate.
- ❌ **Disproven:** every activity in that shape was a user task waiting for a human nobody was ever going to send there — the Twin's own token could never move on its own. Annotation-only was not what "digital twin" was actually required to mean.
- 🔀 **Pivot:** the Twin needed its own, separately generated definition with the human decision points automated ([ADR-002](adr/ADR-002-continuously-running-twin-bpmn.md)).

## Stage 2 — The First Executable Twin (`asyncBefore`)

- 🎯 **Original assumption:** an `asyncBefore` Service Task, with the bridge running the parked job directly, would let the Twin execute without a human.
- 🐛 **Significant bug:** it worked only with `camunda.bpm.job-execution.enabled=false` — the Job Executor otherwise picked the parked job up on its own within a second or two, walking the Twin to completion while the human was still on the very first Original task.
- 🧪 **Empirical investigation:** disabling the Job Executor was tried and *measured*, not just suspected, to break `Task_Approve`'s `PT8H` boundary timer, the grad-admission model's `PT4H` boundary timer, and Camunda's own history cleanup job.
- 🚫 **Rejected alternative:** disabling or reconfiguring the Job Executor in any form, permanently ([ADR-009](adr/ADR-009-no-job-executor-workarounds.md)).
- 🔀 **Pivot:** a wait-state-shaped mechanism was needed instead of an async job — this is what led directly to the Receive Task design.

## Stage 3 — Receive Task / Service Task Split

- 🔍 **Camunda discovery:** a Receive Task is a genuine wait state — a row in `ACT_RU_EXECUTION` with an event subscription beside it, and critically *no* row in `ACT_RU_JOB` — so the Job Executor has nothing to interfere with, and can be left exactly as Camunda ships it.
- 🔀 **Architectural pivot:** every Twin activity becomes a Receive Task (synchronization) immediately followed by a Service Task (automation), both synchronous, inside one Camunda command ([ADR-005](adr/ADR-005-receive-service-task-separation.md)).
- 🐛 **Significant bug:** the Camunda builder API hands out non-deterministic ids for several element kinds on every `generate()` call (the `definitions` root, `conditionExpression`, `bpmn:message`, the whole BPMN DI diagram) — which silently defeated `enableDuplicateFiltering`, so relaunching the very same model kept deploying a new Twin definition version instead of reusing the last one.
- ✅ **Correction:** `stabilizeMessageIds`, `stripDiagramInterchange`, explicit id assignment on the definitions root and condition expressions. Proven by generating the same definition twice and diffing the output byte-for-byte.
- 🧪 **Empirical investigation:** proved (not assumed) that both the Receive Task and the Service Task see the same built-in multi-instance variables inside a wrapping embedded sub-process, via a probe asserting the Service Task read `loopCounter` 0 then 1 across two correlated visits.
- 👁 **Adversarial review:** a dedicated review of the split's implementation found the `multiInstanceLoopCharacteristics`/`loopCardinality` wrapper elements suffered the *same* non-determinism bug as Stage 3's first fix — missed the first time because it only affected models with multi-instance activities. ✅ Fixed with `stabilizeMultiInstanceIds`, verified the same way (generate twice, diff).
- 👁 Also found: an activity id ending in one of the generator's own reserved suffixes (e.g. `Task_A_automate`) collided with its own derived Twin ids, failing deployment with an opaque duplicate-id error. ✅ Fixed with `rejectReservedIdSuffixes`, rejected up front with a clear message instead.
- 👁 Also found: a sequential multi-instance activity's loop cardinality could be a non-literal EL expression (not just a number), which the Twin has no variable to evaluate. ✅ Fixed with a graceful single-visit fallback ([ADR-010](adr/ADR-010-sequential-and-parallel-multi-instance-support.md)).

## Stage 4 — Parallel Multi-Instance

- 🎯 **Original assumption:** `MessageCorrelationBuilder.localVariableEquals(...)` or `.processInstanceVariableEquals(...)` would disambiguate which parallel sibling should be released.
- ❌ **Disproven:** both tried and empirically failed to disambiguate — wrong scope, and no disambiguation at all, respectively.
- 🎯 **Original assumption:** `Execution.getParentId()` would exist on the public API and could be used to walk to a shared identifying ancestor.
- ❌ **Disproven:** verified absent from the public API via direct `javap` inspection of the real jar.
- 🔍 **Camunda discovery:** `RuntimeService.messageEventReceived(messageName, executionId)` targets one named execution directly, bypassing correlation-key matching entirely — found via `javap` inspection after the above two approaches failed.
- 🔍 **Camunda discovery:** the non-local vs. local `loopCounter` scope asymmetry — on the Twin's receive-task side, `loopCounter` is local to a *parent* scope execution one level above the event-subscribed execution (needs a non-local `getVariable()`); on the Original's plain-user-task side, `loopCounter` is local directly to the "start" event's own execution. Proven with a probe (three parallel siblings, each released individually), not assumed symmetric.
- ✅ **Correction/completion:** `resolveParallelSibling` ([ADR-007](adr/ADR-007-execution-targeted-messaging.md)), lifting an earlier restriction that had fallen every parallel activity back to a single visit while the correct mechanism was being found.
- 👁 **Adversarial review:** found the manual Bridge button (as opposed to the automatic trigger) had no live `ExecutionEvent` to read an execution id from, so it always hit the plain-`correlate()` path and threw `MismatchingMessageCorrelationException` for any parallel activity with more than one open sibling. ✅ Fixed by consolidating the bridge and advance calls behind one shared method (`bridgeActivityEvent`) so both callers get identical parallel-multi-instance disambiguation.

## Stage 5 — Incident Handling

- 🎯 **Original question, deliberately not pre-answered:** what should happen when a Twin activity's automation throws? The investigation was explicitly scoped, in order, before any code was written: first, could a standard Camunda mechanism handle it at all; second, what should the *policy* be (Fail-Fast / Retry / Incident-Driven / Recoverable); third, only then, implement the smallest mechanism that fits.
- 🧪 **Empirical investigation:** proved transaction rollback semantics for a failed synchronous command — the *whole* command (including the Receive Task's own correlation) rolls back, leaving the Twin's wait state untouched and safe to retry.
- 🔍 **Camunda discovery:** Error Boundary Events catch even a plain unchecked exception thrown inside a synchronous Service Task, not just an explicitly-thrown `BpmnError` — a genuinely viable BPMN-native retry-loop mechanism, deliberately not built (see [ADR-008](adr/ADR-008-incident-driven-failure-policy.md)'s Alternatives section for why).
- 🔀 **Pivot / decision:** Incident-Driven, via `runtimeService.createIncident(...)`, not automatic retry — because `ProjectAutomationService.execute()` carries no documented idempotency contract.
- 🐛 **Significant bug:** the first version of the execution-lookup for attaching an Incident (`createExecutionQuery()` + `getActiveActivityIds().contains(...)`) threw `BadUserRequestException: Execution must be related to an activity: activity is null` — root cause: that query pattern can return a *scope* execution that merely sees the activity through a descendant, without being positioned there itself, and `createIncident` requires the actual leaf. ✅ Fixed by reusing the already-proven `ActivityInstance`-tree-walking pattern instead.
- 🧪 **Empirical investigation:** a diagnostic-instrumented probe observing actual execution ids/states was what led to the correct root cause and fix above, not guessing.

## Stage 6 — Phase 7: The Red-Team Review

- 👁 **Adversarial review:** a three-way parallel, independent red-team review across Runtime, Synchronization, Execution Identity, BPMN Coverage, Failure Recovery, Performance, and Maintainability, explicitly instructed *not* to fix anything found — expose flaws, don't hide them. Found eight weaknesses (W1–W8) plus confirmed, via direct `javap` inspection, that `camunda-bpmn-model` 7.22.0 has no `AdHocSubProcess` class at all — a genuine library limitation, not an implementation gap.

## Stage 7 — Phase 7.5: Closing W1, W2, W4

- ✅ **W1 correction (many-to-one activity mapping):** `connectActivity` enforced as a true bijection ([ADR-014](adr/ADR-014-one-to-one-activity-link-mapping.md)).
- 👁 **Adversarial review of the W1 fix itself:** found the check-then-mutate sequence was not atomic across concurrent callers — three separate `CopyOnWriteArrayList` operations, not one. ✅ Fixed with `synchronized (twin)`. A *second* review specifically targeted the new lock for deadlock and stale-monitor risk and ran the concurrency regression five times clean; found nothing further.
- ✅ **W2 correction (unsupported BPMN constructs):** real Inclusive Gateway support added, closing a piece of dead code in `copyDefaultFlows()`; every other unsupported construct changed from a silent warn-and-drop to a thrown, precise diagnostic ([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md)).
- ✅ **W4 correction, attempt 1 (restart-safe dedup):** replaced the in-memory `forwardedBridgeActivities` Set with a check on `evolvedAgent_<twinActivityId>[_loopCounter]` variable existence.
- 👁 **Adversarial review:** found attempt 1 correct for multi-instance visits but wrong for a plain activity revisited through an ordinary loop-back gateway — every such visit writes the identical variable name, so visit #2 read as "already forwarded" the instant visit #1 succeeded. Reproduced with a real regression run, not just inspection.
- ✅ **W4 correction, attempt 2:** compared visit ordinal (by start time) against how many times the Twin's *automation task* had finished.
- 🐛 **Significant bug, found by re-running the full suite alongside the fix:** attempt 2 broke the pre-existing `TwinAutomationIncidentTest` retry regression — a failed automation rolls its command back (including its own historic instance) but *not* the evolve step's already-committed variable write, so counting automation completions wrongly read a genuinely-already-evolved, automation-still-pending retry as "not yet evolved."
- 🧪 **Empirical investigation:** confirmed via a throwaway probe (`ZzHistoricDetailProbeTest`) that `historyService.createHistoricDetailQuery().variableUpdates()` returns one row *per update*, not one collapsed current value the way `HistoricVariableInstance` does — before relying on it.
- ✅ **W4 correction, final (attempt 3):** count *sets* of `evolvedAgent_<twinActivityId>` via `HistoricDetail.variableUpdates()` instead of automation completions ([ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)). Both the loop-back regression and the incident-retry regression pass together.
- 🐛 **Separately found, unrelated to W1/W2/W4:** a purely cosmetic Camunda 7.22 quirk — `ProcessDefinition.getId()` occasionally omits its `key:version:deploymentId` composite shape under certain deployment patterns. ✅ Confirmed to have zero functional effect (nothing in the codebase parses that string's shape); the one affected test assertion was corrected to check `getKey()` directly.
- 👁 **Final validation pass:** full three-way independent review (fresh race-fix review, a second independent W2/W4 re-audit, and a scope-diff audit against the "do not touch" list) — found nothing further beyond what's recorded above. The scope-diff audit's own raw findings needed one methodological correction: it flagged most of the protected list as "changed" because it ran a plain `git diff` against a repository with no intermediate commits separating work sessions, and could not distinguish this pass's edits from earlier phases of the same build. Cross-checked directly against the actual W1/W2/W4 edit list (enumerated in [PROF_QA_PREP.md](../../PROF_QA_PREP.md)'s Sixth round) and confirmed the audit's individual per-file observations were accurate — only its "this diff" framing was wrong.

## Stage 8 — 🏁 Final Convergence, Version 1.0

- Full backend suite (`wbapi` + `nodemanager`) green, twice consecutively, after the last correction.
- All three Phase 7 high-priority findings (W1, W2, W4) closed, each with a dedicated regression test proven to fail before its fix and pass after.
- Every fix passed through at least one independent adversarial review after implementation — three of them (W1's concurrency fix, W4's two intermediate attempts) were found wrong by that review and corrected before shipping, which is itself the strongest evidence in this project that "never call something done without an adversarial pass" is a load-bearing practice, not a formality.
- Working tree confirmed to match the expected set of changed/new files exactly (`git status --short`), with no stray probe files left behind at any point.
- W3, W5–W8 (Phase 7's remaining findings) and the `AdHocSubProcess` Camunda limitation remain open and out of scope for Version 1.0 by explicit decision, recorded in Architecture Specification Section 9 and Section 10, not silently dropped.
