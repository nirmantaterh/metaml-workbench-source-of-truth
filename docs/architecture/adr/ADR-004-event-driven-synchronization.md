# ADR-004: Event-Driven Synchronization via `AFTER_COMMIT`, Never Polling

**Status:** Accepted (Version 1.0)

**Scope:** This decision covers only the in-process Original↔Twin bridge described in
[ARCHITECTURE.md](../ARCHITECTURE.md) (one shared Camunda engine, one JVM). It does not describe
and is not a claim about the separately-generated, cross-process Target Platform pipeline (Proxy/Twin
synchronized over RabbitMQ via a generated `SignalBroadcaster`), which did not exist when this ADR
was written and uses a different mechanism: signal-driven advancement with a 1-second polling
coordinator (`@Scheduled(fixedDelay = 1000)`). See `TEAM_DEMO_GUIDE.md` §14.4 for that mechanism.

## Context

The Twin needs to learn "the Original just did something" as close to instantaneously as possible, without the two instances being coupled by shared application state, and without introducing a scheduled poll that would add latency, load, and a whole class of "did I already handle this" bugs.

## Decision

Synchronization is driven by `AutoBridgeTrigger.onActivityStarted`, a `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` on Camunda's own `ExecutionEvent`, republished by the Camunda Spring Boot Starter's `EventPublisherPlugin`. The Original committing an activity-start is the *only* trigger; nothing in this system ever asks "has the Original moved yet?"

## Alternatives Investigated

- **Polling** (a scheduled task periodically diffing Original state against Twin state) — rejected outright; it is the exact pattern the whole project brief asked to avoid ("the event replaces polling, not the database" — recorded in earlier session context), and it would need its own notion of "what changed since last poll" that Camunda's event stream already provides for free.
- **A plain `@EventListener`** (not transaction-phase-aware) — tried and empirically disproven: it fires *before* the engine's own commit flushes, so `runtimeService`/`historyService` queries made from inside it read every activity as "not yet reached." Cost real debugging time to discover, recorded verbatim in `AutoBridgeTrigger`'s own comment ("cost me an afternoon").
- **A custom message bus / webhook between the two instances** — not seriously pursued; it would duplicate what Spring's transaction synchronization + Camunda's own event publishing already provide natively, in violation of "Camunda-native mechanisms over custom infrastructure."

## Evidence

`AutoBridgeTrigger.java`'s own inline comment documents the plain-`@EventListener` failure directly. Every walkthrough test (`WireTransferWalkthroughTest`, `TwinExecutionWalkthroughTest`) exercises this path implicitly: completing an Original task and immediately asserting the Twin has already advanced, with no sleep or wait anywhere in the test — proof the synchronization is synchronous-enough-to-observe-immediately from the calling thread's perspective, not eventually-consistent on some polling interval.

## Trade-offs

- **Gained:** near-instant synchronization with zero added latency budget, no polling infrastructure, no "missed event" window beyond ordinary transaction-commit semantics.
- **Given up:** synchronization logic is now coupled to Spring's transaction-synchronization lifecycle, which is a more specialized mechanism than a generic listener and required the `AFTER_COMMIT` phase to be discovered empirically rather than being obvious from the API surface.

## Consequences

- Any code that needs to react to an Original activity being reached must go through this same `AFTER_COMMIT`-phase listener pattern, or it will inherit the exact "reads as not-yet-reached" bug this ADR's evidence describes.
- The listener must never let an exception escape to the caller (Section 4 of the Architecture Specification) — a design constraint that flows directly from choosing a synchronous, in-process trigger over a decoupled queue.

## Future Reconsideration

Would be revisited only if this system needed to synchronize across process boundaries (e.g. a Twin running in a different JVM/engine than the Original), at which point `AFTER_COMMIT` alone would no longer suffice and a real message broker would need its own ADR.
