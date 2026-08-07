# ADR-014: Activity Links Are Enforced as a One-to-One Bijection

**Status:** Accepted (Version 1.0) — corrected in Phase 7.5 (previously unenforced)

## Context

`connectActivity` is the single place an `ActivityLink` (`originalActivityId` ↔ `twinActivityId`) is created (ADR-006). Prior to Phase 7.5, it only enforced uniqueness from the Original activity's side (`removeIf` keyed on `originalActivityId`) — nothing prevented two *different* Original activities from both linking to the *same* Twin activity.

## Decision

`connectActivity` now rejects a link if the requested Twin activity is already claimed by a *different* Original activity, while still allowing the same Original activity to re-point its own link elsewhere (which correctly frees the old Twin activity for another link). The check-then-mutate sequence is additionally synchronized per-twin (`synchronized (twin)`, using the shared `TwinProcess` object already held one-per-id in a `ConcurrentHashMap` as the lock) to make the whole operation atomic against concurrent callers.

## Alternatives Investigated

- **Enforce the invariant during Twin generation instead of at link time** — considered per Phase 7.5's own explicit instruction to evaluate multiple locations (`ActivityLink` creation, `connectActivity`, Twin generation, synchronization validation) and pick the one with the strongest guarantee for the smallest implementation cost. Rejected: `TwinModelGenerator` has no visibility into `connectActivity` calls at all (generation happens once, at launch, entirely before any link is ever created) — this invariant is fundamentally about link *creation*, not model *shape*.
- **Enforce it at synchronization time** (reject an ambiguous bridge/evolve call if two links happen to collide) — rejected: this would let a broken (many-to-one) link exist silently in `TwinProcess.activityLinks` for an arbitrary period, only surfacing as a confusing failure the first time it happens to matter, rather than being refused the moment the mistake is made.
- **Leave `connectActivity` unsynchronized** — the actual first version of this fix. An independent adversarial review found the check (`stream`/`filter`/`findFirst`) and the mutation (`removeIf` + `add`) were three separate `CopyOnWriteArrayList` operations, thread-safe individually but not as a sequence — two concurrent `connectActivity` calls linking different Originals to the same still-unclaimed Twin activity could both pass the check before either `add()` became visible to the other, reproducing the exact many-to-one state this ADR exists to prevent.

## Evidence

Why this matters concretely: `evolvedAgent_<twinActivityId>` and the Twin's own advance message (`TwinAdvance_<twinActivityId>`) are both keyed on `twinActivityId` alone — a second Original activity sharing one would silently clobber whatever the first had written instead of getting its own slot, with no error anywhere to indicate it happened. `connectRejectsATwinActivityAlreadyClaimedByADifferentOriginalActivity` is the dedicated regression for the mapping rule itself. `concurrentConnectsToTheSameTwinActivityNeverBothSucceed` (a `CyclicBarrier`-gated race between two callers) is the dedicated regression for the concurrency fix, independently verified by a second adversarial review specifically targeting the new lock for deadlock potential (confirmed: only two `synchronized` blocks exist anywhere in the codebase, with no cycle between them) and stale-monitor risk (confirmed: no code path ever replaces a live `TwinProcess` for an id already in use), and run five times back to back clean.

## Trade-offs

- **Gained:** it is now structurally impossible to create a many-to-one activity link, whether by a single mistaken API call or by a race between two concurrent ones.
- **Given up:** none identified — the rejection is a `400`-class `IllegalArgumentException` at the exact point of the mistake, which is strictly more useful to a caller than silent corruption discovered later.

## Consequences

- Any future API surface that creates or mutates `ActivityLink`s must go through `connectActivity` (or an equivalent method carrying the same invariant check and the same lock), not a direct mutation of `TwinProcess.activityLinks`.

## Future Reconsideration

None anticipated; this closes a correctness gap rather than making a debatable trade-off.
