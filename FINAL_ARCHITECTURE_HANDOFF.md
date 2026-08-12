# MetaML Workbench — Final Architecture Handoff

**Purpose:** context for an independent adversarial review. This describes the repository as it
exists now. The code is authoritative; where this document and the code disagree, the code wins.

**Verified at time of writing:** `./mvnw test` → **230 tests, 0 failures** (workbench 148,
wbapi 76, nodemanager 6).

---

## 1. Executive Summary

MetaML Workbench is a Camunda/Spring Boot platform for modelling a BPMN process, generating a
standalone Spring Boot application from it, and running a **twin** of that process under
tenant-scoped governance.

Two distinct product paths share one model:

1. **Model → Generate → Launch.** A saved BPMN model is turned into a complete, standalone Spring
   Boot + Camunda project on disk and launched as its own child JVM on an auto-assigned port.
2. **Model → Twin → Connect → Evolve/Bridge.** A generated *twin* process definition runs alongside
   the original process instance. Evolving an activity asks a node manager for an agent and records
   it on the twin — but only after governance allows it.

**Subsystems**

| Subsystem | Where |
|---|---|
| Model lifecycle + orchestration | `workbench/service/WorkbenchServiceImpl` (the hub) |
| Project generation / launch | `workbench/generation/*` |
| Twin generation + execution | `workbench/bpmn/TwinModelGenerator`, `workbench/delegate/*`, `workbench/bridge/AutoBridgeTrigger` |
| Tenant governance (durable) | `workbench/governance/*` |
| Platform governance (runtime-only) | `workbench/service/GovernanceServiceImpl` |
| Approvals | `workbench/governance/Approval*` |
| Persistence | `workbench/store/*`, `workbench/workflow/*`, `workbench/governance/*Store` |
| REST API | `wbapi/controller/workbench/*` |
| Agent catalogue | `nodemanager/*` (separate service, port 8083) |
| Frontend | `frontend/src/pages/workbench/*` (React + bpmn.io) |

**Capabilities:** model CRUD + deletion with permanent ID retirement; per-activity BPMN→REST
endpoint generation; generated-project persistence across restart; latest-generation-only retention;
generated-app liveness detection; twin Connect/Evolve/Bridge; ALLOW/DENY/REQUIRE_APPROVAL governance
with policy versioning; approval persistence, tenant isolation and restart reconciliation;
governance + approval UI.

**Status: COMPLETE** in the sense defined in §20 — not "bug-free".

---

## 2. Final Architecture Tree

```
MetaML Workbench
│
├── Model Lifecycle ......................... WorkbenchServiceImpl
│   ├── saveProcessModel ................... validates id, deploys to Camunda, writes .bpmn
│   │   ├── SAFE_MODEL_ID ([A-Za-z0-9_-]+)  path-traversal guard
│   │   └── isRetiredModelId ............... rejects reuse of a deleted id
│   ├── listProcessModels / getProcessModel
│   ├── generateSpringBootProject .......... per-model lock; records GENERATE
│   ├── launchGeneratedProject / stopGeneratedProject
│   ├── deleteProcessModel ................. authoring-scope delete (§12)
│   └── WorkflowStateTracker ............... MODEL → GENERATE → LAUNCH breadcrumb
│
├── Generated Applications .................. workbench/generation
│   ├── BpmnActivities ..................... execution-semantic activity classification
│   │   └── Trigger: USER_TASK | RECEIVE_TASK | EXTERNAL_TASK
│   ├── DelegateClassGenerator ............. one class per unique delegateExpression
│   ├── SpringBootProjectGenerator ......... copy template, inject BPMN + delegates + controller
│   │   ├── generate() ..................... new UUID dir every call
│   │   ├── scanExisting() ................. restart reconstruction from the directory itself
│   │   └── delete() ....................... containment-checked recursive delete
│   └── SpringBootProjectLauncher .......... child JVM per project
│       ├── running{} ...................... runtime-only registry
│       ├── launchLocks{} .................. ReentrantLock per projectId
│       ├── find/listRunning ............... query-time Process.isAlive() + self-heal
│       ├── runIfIdle / runIfAllIdle ....... the cleanup safety gate
│       └── somethingIsListeningOn ......... restart-only hard-kill guard
│
├── Twin / Execution
│   ├── TwinModelGenerator ................. builds an executable twin definition
│   ├── TwinProcess ........................ id, modelId(provenance), tenantId, activityLinks, eventLog
│   ├── connectActivity .................... original activity ↔ twin activity
│   ├── evolveActivity / bridgeActivityEvent
│   ├── AutoBridgeTrigger .................. AFTER_COMMIT listener, off the engine thread
│   ├── AgentExecutionDelegate ............. reads evolvedAgent_*, records to event log
│   └── NodeManagerClient .................. HTTP to nodemanager (agent availability)
│
├── Governance
│   ├── PLATFORM (runtime-only) ............ GovernanceServiceImpl
│   │   ├── deniedAgentTypes, maxEvolutionsPerTwin, maxTwinExecutionsPerTwin
│   │   └── per-twin quota counters (reset on restart, by contract)
│   └── TENANT (durable) ................... TenantPolicyService + PolicyDecisionEngine
│       └── Tenant → Policy → PolicyVersion → PolicyRule
│
├── Approvals
│   ├── Approval ........................... immutable record, pinned policy decision
│   ├── ApprovalService .................... create / get / list / mark* (no delete)
│   └── ApprovalStore ...................... file-backed
│
├── Persistence
│   ├── WorkbenchStateStore ................ models + twins
│   ├── WorkflowEventStore ................. per-model stage events
│   ├── ApprovalStore, TenantPolicyStore
│   ├── ProcessModelFileStore .............. data/models/{id}.bpmn
│   ├── data/generated-projects/{projectId}/
│   └── H2 file DB ......................... Camunda deployments + instances
│
├── Frontend (React)
│   ├── ModelPage .......................... bpmn.io editor, Save/Generate/Launch, breadcrumb
│   ├── EditProjectListPage ................ list + delete
│   ├── EvolvePage ......................... Deploy twin, Connect, Evolve, Bridge, policy/usage
│   ├── DeployedAppsPage ................... running generated apps, Stop, "Evolve this"
│   ├── GovernancePoliciesPage ............. tenant/policy/version/rule lifecycle
│   └── GovernanceApprovalsPage ............ approve/reject with confirmation
│
└── Test Infrastructure
    ├── @IsolatedWorkbenchTest ............. meta-annotation, all 7 isolation properties
    ├── unit tests w/ @TempDir stores ...... intentionally real persistence
    └── fake mvnw.cmd listener ............. stands in for a real generated app
```

---

## 3. End-to-End Architecture

### Primary product flow

```
 ProcessModel ──(tenantId set once, at creation)
      │  deploy to Camunda + write data/models/{id}.bpmn
      ▼
 Generate ──► generated-projects/{newUUID}/   [always a NEW dir; never in-place]
      │       records GENERATE/COMPLETED detail = projectId
      │       then collects superseded generations (retention)
      ▼
 Launch ──► child JVM (ProcessBuilder), auto-assigned port, SERVER_ADDRESS=127.0.0.1
      │     records LAUNCH/COMPLETED detail = "port N"
      ▼
 ══════════ PROCESS BOUNDARY ══════════ generated app is standalone, no callback to MetaML

 ProcessModel ──► launchProcess()
      │   starts TWO Camunda instances: the original, and a generated twin definition
      ▼
 TwinProcess (id, modelId=provenance, tenantId inherited from model)
      │
      ▼  connectActivity(originalActivityId → twinActivityId)
      │
      ├── evolveActivity(twinId, activityId, agentType)      [manual]
      └── bridgeActivityEvent(...)                           [manual button + AutoBridgeTrigger]
                │
                ▼   both converge on ──► runEvolution()
      ┌─────────────────────────────────────────────────────┐
      │ 1. GovernanceServiceImpl.reserveEvolutionSlot        │  platform quota
      │ 2. if twin.tenantId != null → enforceTenantPolicy    │  tenant policy
      │      PolicyDecisionEngine → ALLOW / DENY / REQUIRE_APPROVAL
      └─────────────────────────────────────────────────────┘
                │
      ┌─────────┼──────────────────┬──────────────────────┐
    ALLOW     DENY          REQUIRE_APPROVAL        eval failure
      │         │                  │                      │
      │      return           Approval PENDING        DENY (fails closed)
      │      blocked               │
      │                    approve │ reject → REJECTED
      ▼                            ▼
 executeAfterGovernance ◄──────────┘   (pinned decision; engine NOT re-consulted)
      │
      ├─ NodeManagerClient.checkAgentAvailability(agentType)
      ├─ runtimeService.setVariable(twinInstance, evolvedAgent_<act>[_loop], agentName)  ← THE side effect
      └─ writeAgentOutputs(...)
```

### BPMN → generated endpoints

```
 BPMN XML
    │
    ▼  BpmnActivities.eligible(model)      — classification by EXECUTION SEMANTICS, not element type
    │     eligible ⇔ the engine parks the token and will never move it on its own
    │       • camunda:type="external"  → EXTERNAL_TASK   (checked FIRST; overrides element type)
    │       • UserTask                 → USER_TASK
    │       • ReceiveTask              → RECEIVE_TASK
    │       • everything else          → not eligible (runs on arrival / engine-driven)
    ▼
 One endpoint per eligible activity:  POST /api/v1/process/{processInstanceId}/{slug}/complete
    │   slug from display name → element id → "activity"; collisions disambiguated in doc order
    │   the ACTIVITY ID is emitted as a literal — an endpoint physically cannot reach another activity
    ▼
 GeneratedProcessController (written into the project)
    │   USER_TASK    → TaskService.complete by taskDefinitionKey (all open instances)
    │   RECEIVE_TASK → runtimeService.signal on each waiting execution
    │   EXTERNAL_TASK→ externalTaskService.lock then complete (this controller IS the worker)
    │   empty match  → HTTP 409 (token is not at that activity)
    ▼
 Generated Spring Boot app → its own embedded Camunda engine → process execution
```

### Boundaries

| Boundary | Meaning |
|---|---|
| **Trust** | `tenantId` is caller-supplied; **no authentication exists** (§9) |
| **Process** | Generated apps are separate JVMs with zero coupling back to MetaML |
| **Authority** | Launcher registry = liveness; workflow history = pipeline history; filesystem = project identity |
| **Ownership** | Model owns its `.bpmn` + generated projects. Model does **not** own twins or Camunda state |
| **Governance** | Platform (runtime-only) evaluated before tenant (durable) |

---

## 4. Before vs After

```
BEFORE                                    AFTER
──────────────────────────────────        ─────────────────────────────────────────────
Model                                     Model
  └─ no tenant                              ├── tenantId (set once at creation)
  └─ no delete                              ├── deletion (authoring-scope)
  └─ id freely reusable                     └── permanent ID retirement
      ↓                                         ↓
Generate                                  Generate
  └─ dirs accumulate forever                ├── latest-generation-only retention
  └─ lost on restart                        ├── restart reconstruction (scan + history)
      ↓                                     └── superseded collected when idle
Launch                                        ↓
  └─ "running" forever after a crash      Launch
      ↓                                     ├── query-time Process.isAlive() + self-heal
Twin                                        └── restart-only port guard (hard kill)
      ↓                                         ↓
Evolve                                    Twin ── survives model deletion (provenance only)
  └─ platform quota only                      ↓
                                          Evolve / Bridge  (all paths funnel here)
                                              ↓
                                          Governance
                                            ├── PLATFORM quota ....... runtime-only
                                            └── TENANT policy ........ durable, versioned
                                                  ├── ALLOW ──────────────┐
                                                  ├── DENY (also on eval failure)
                                                  └── REQUIRE_APPROVAL    │
                                                        ↓                 │
                                                     Approval (pinned)    │
                                                        ├── REJECTED      │
                                                        └── APPROVED ─────┤
                                                              ↓           ▼
                                                        restart reconciliation → Execution
```

| Area | Before | Now |
|---|---|---|
| Tenant ownership | none | Model → Twin → Evolve, set once at creation |
| Governance | platform quota only | + durable tenant policy, versioned, first-match-in-version |
| Approvals | none | persisted, tenant-scoped, policy-pinned, reconciled on restart |
| Model deletion | none | model + `.bpmn` + all its projects; runtime preserved |
| Model IDs | reusable | permanently retired after successful creation + deletion |
| Generated projects | unbounded accumulation | one retained per model; superseded collected when idle |
| Restart | generated projects lost | reconstructed from disk + workflow history |
| Liveness | trusted stale registry | `Process.isAlive()` per read + self-healing removal |
| BPMN endpoints | one generic completion endpoint | one per eligible activity, semantics-aware |
| Frontend | model editor only | + governance, approvals, deployed apps, delete |
| Test isolation | none (tests wrote real dev data) | `@IsolatedWorkbenchTest`, all 5 stores |

---

## 5. State Ownership

| State | Authoritative source | Persisted | Derived | Runtime-only | Survives restart |
|---|---|---|---|---|---|
| ProcessModel | `workbench-state.json` | ✅ | ✗ | ✗ | ✅ |
| BPMN artifact | `data/models/{id}.bpmn` | ✅ | ✗ | ✗ | ✅ |
| TwinProcess | `workbench-state.json` | ✅ | ✗ | ✗ | ✅ |
| Workflow history | `workflow-events.json` | ✅ | ✗ | ✗ | ✅ |
| Generated-project dir | the directory itself | ✅ | ✗ | ✗ | ✅ |
| `generatedProjects` map | — | ✗ | ✅ `scanExisting()` | ✗ | rebuilt |
| `modelIdByProjectId` | — | ✗ | ✅ latest `GENERATE/COMPLETED` | ✗ | rebuilt |
| Generated JVM registry | `SpringBootProjectLauncher.running` | ✗ | ✗ | ✅ | ✗ |
| Approval | `approvals.json` | ✅ | ✗ | ✗ | ✅ |
| Tenant / Policy / Version / Rule | `tenant-policies.json` | ✅ | ✗ | ✗ | ✅ |
| Camunda deployments + instances | H2 file DB | ✅ | ✗ | ✗ | ✅ |
| Platform quota counters | `GovernanceServiceImpl` | ✗ | ✗ | ✅ | ✗ (by contract) |
| Platform policy overrides | `GovernanceServiceImpl` | ✗ | ✗ | ✅ | ✗ (by contract) |
| `evolutionsInFlight` claims | `WorkbenchServiceImpl` | ✗ | ✗ | ✅ | ✗ |
| Model / project locks | in-memory maps | ✗ | ✗ | ✅ | ✗ |

**Reading:** everything in the "Derived" column is reconstructed at startup and can be deleted
without loss. Everything "Runtime-only" is *intended* to vanish. The authoritative durable set is
the five files plus the H2 database.

---

## 6. Model Lifecycle

```
 create ── saveProcessModel(id?, name, bpmnXml, tenantId?)
   │  ├─ id supplied → SAFE_MODEL_ID check → not already present → NOT retired
   │  ├─ id absent   → UUID
   │  ├─ deploy to Camunda; require exactly one executable process
   │  ├─ write data/models/{id}.bpmn   (rollback deploy + map entry on failure)
   │  └─ MODEL: IN_PROGRESS → COMPLETED     (FAILED on any throw)
   ▼
 edit ── the editor saves a NEW model; saveProcessModel never overwrites an existing id
   ▼
 generate ── generateSpringBootProject(modelId)          [holds per-model lock]
   │  ├─ GENERATE: IN_PROGRESS → COMPLETED(detail = new projectId)
   │  └─ cleanupSupersededProjects(modelId)
   ▼
 regenerate ── same call again: a brand-new UUID directory; previous becomes superseded
   ▼
 launch ── launchGeneratedProject(projectId)             [holds per-project lock]
   │  └─ LAUNCH: IN_PROGRESS → COMPLETED(detail = "port N")
   ▼
 stop ── stopGeneratedProject(projectId)
   │  ├─ LAUNCH: STOPPED (detail = the port it was on)
   │  └─ cleanupSupersededProjects(model)      ← deferred retention comes due here
   ▼
 external death ── no MetaML call at all
   │  └─ next find()/listRunning() observes !isAlive(), removes the entry, reports not-running
   ▼
 restart ── restoreState()
   │  ├─ load models + twins; backfill MODEL/COMPLETED only for models with NO history
   │  ├─ restoreGeneratedProjects(): scanExisting() → generatedProjects
   │  │      map modelIdByProjectId from each model's latest GENERATE/COMPLETED
   │  │      if a recorded LAUNCH port is still listening → SKIP cleanup for that model
   │  │      else cleanupSupersededProjects(...)
   │  └─ reconcileApprovedApprovals()
   ▼
 delete ── deleteProcessModel(modelId)                   [model lock + ALL its project locks]
```

**Retention (latest-generation-only).** "Current" = detail of the last **`GENERATE/COMPLETED`**
event — deliberately *not* `stages().get(GENERATE).detail()`, because after a failed regenerate the
folded view's detail is an error message, not a project id. Superseded = every earlier distinct
completed generation.

**Protections.** `runIfAllIdle` takes the per-project `ReentrantLock` (sorted, `tryLock`) for every
target *before* checking liveness, then verifies none is running. A launch holds that same lock for
its whole duration — including the multi-minute window before the app is listening — so a project
being launched is never seen as idle. Failure to acquire simply defers cleanup; it never fails the
operation that triggered it.

**Deletion.** Refuses with `IllegalStateException` (HTTP 409) if any of the model's generated apps
is running or launching; deletes model + `.bpmn` + **all** its generations + runtime mappings; leaves
twins, Camunda, approvals, tenants and workflow history untouched.

**ID retirement.** `isRetiredModelId(id)` = history contains `MODEL/COMPLETED` **and** no current
model holds that id. Keyed on `COMPLETED` specifically so a *failed* save (which records
`IN_PROGRESS → FAILED`) can still be retried with the same id.

---

## 7. Generated Project Lifecycle

```
 Generate P1 ──► P1 current
      │
 Generate P2 ──► P2 current, P1 superseded
      │
      ▼
 Is P1 running or mid-launch?   (per-project lock + Process.isAlive())
      ├── YES → retain; retry at the next lifecycle event
      └── NO  → delete directory, drop from generatedProjects + modelIdByProjectId
```

- **Launcher registry** (`running`) is runtime-only, keyed by projectId, holding `(LaunchedProject, Process)`.
- **`Process.isAlive()`** is re-checked on every `find()`/`listRunning()`; a dead entry is removed
  with a *conditional* `remove(key, value)` so a concurrent relaunch under the same id is never
  clobbered.
- **Cleanup triggers** are existing lifecycle events only — regenerate, stop, restart. There is no
  daemon, scheduler, or background sweep.
- **Locks:** one `ReentrantLock` per projectId, never removed (a stopped project can be relaunched
  under the same id). Lock order is always **model lock → project locks**.
- **Path safety:** `SpringBootProjectGenerator.delete(projectId)` resolves and normalises the path
  and refuses anything that is not a direct child of the output directory.

### After a MetaML hard kill

`@PreDestroy` (`stopEverythingStillRunning`) never runs, so the previous run's generated JVMs stay
alive and keep their ports, while the new instance starts with an empty registry. To stop cleanup
deleting a live project's source, `restoreGeneratedProjects` reads every `"port N"` recorded in a
model's `LAUNCH` history and probes `127.0.0.1:port`. If anything answers, **all** cleanup for that
model is skipped for this startup, with a warning.

The probe is deliberately **restart-only** — during normal operation the registry is the sole
liveness authority and a probe could only introduce a competing source of truth. It **fails closed**:
a listening port proves *something* is there, not that it is the generated app, and over-retention is
recoverable while deleting a running app's source is not. It cannot see a leaked app whose port was
never recorded.

---

## 8. Twin / Execution Architecture

**TwinProcess does NOT own the ProcessModel — it retains provenance only.**
`TwinProcess.getModelId()` has exactly one call site in `main/`: serialization in
`WorkbenchStateStore`. Nothing resolves a twin's `modelId` back to a `ProcessModel`. After
`launchProcess` returns, the twin carries `processDefinitionId` / `twinProcessDefinitionId` (Camunda
ids), and Connect/Evolve/Bridge/advance operate on the twin and Camunda alone. A twin is fully
functional after its source model is deleted.

```
 launchProcess(modelId)
   ├─ deployTwinDefinition(model)   — generated FROM THE DEPLOYED definition, not the stored XML
   │     name = "<model name> (twin <modelId>)", enableDuplicateFiltering(true)
   │     ⇒ ONE twin deployment is SHARED by every twin of that model
   ├─ start original instance   (businessKey = original:<twinId>)
   └─ start twin instance       (businessKey = twin:<twinId>)   — rolls back the original on failure

 connectActivity(twinId, originalActivityId, twinActivityId)
   ├─ both ids validated against their OWN definitions
   ├─ one twin activity may serve only one original activity
   └─ synchronized on the twin (check-then-replace is not atomic on a COW list)

 Execution path
   original activity starts
        │  ExecutionEvent (engine thread)
        ▼
   AutoBridgeTrigger  @TransactionalEventListener(AFTER_COMMIT)  → own executor, 6s timeout
        │   (off the engine thread on purpose: a twin-side failure must not roll back
        │    the human's task-completion transaction)
        ▼
   bridgeActivityEvent(twinId, activityId, activityInstanceId)
        ├─ claim evolutionsInFlight[twinId:activityInstanceId]  (held across evolve AND advance)
        ├─ bridgeOnce → alreadyEvolved? → runEvolution(...)      → governance
        └─ advanceTwinActivity(...)  — correlates the twin's receive-task message
```

**Real side effect.** The only durable effect of an evolution is
`runtimeService.setVariable(twinInstance, "evolvedAgent_<twinActivityId>[_<loopCounter>]", agentName)`
plus optional agent-output variables. If that variable cannot be set (typically the twin already
ended) the decision is reported **not approved**. That variable, in Camunda's own committed history,
is the idempotency key used by duplicate-suppression and restart reconciliation.

`AgentExecutionDelegate` (complete listener on the original's task) only *reads* that variable and
records event-log entries — it never invokes an agent and never bypasses governance.

---

## 9. Governance Architecture

```
 Tenant ──► Policy ──► PolicyVersion ──► PolicyRule
                          (DRAFT/ACTIVE)   field, operator, value, effect
                              │
                              ▼
                     PolicyDecisionEngine.evaluate(GovernanceRequest)
                              │
              ┌───────────────┴────────────────┐
      platform policies (tenantId == null)   tenant policies
              │                                │
        first matching rule                first matching rule
        in the ACTIVE version              in the ACTIVE version
              └────────────► pickWinner ◄──────┘
                     higher severity wins;
                     TIE → PLATFORM wins
                              │
                ALLOW / DENY / REQUIRE_APPROVAL
```

**Two distinct layers.**

| | Platform (`GovernanceServiceImpl`) | Tenant (`TenantPolicyService` + engine) |
|---|---|---|
| Purpose | runaway guard rail per running instance | authored business rules |
| Content | denied agent types, max evolutions/twin, max twin executions/twin | Policy → Version → Rule |
| Durability | **runtime-only** — config at startup, in-memory overrides, counters reset on restart | **durable** (`tenant-policies.json`) |
| Evaluated | first, in `runEvolution` | second, only if `twin.tenantId != null` |

The platform layer's runtime-only nature is an explicit, tested contract
(`GovernanceServiceImplTest`), not lost state: a restart returns configured defaults and zeroed
counters. Restart reconciliation depends on this — a persisted exhausted counter would refuse an
approval that was already granted before the crash.

**Ordering / semantics**
- Platform quota first (`reserveEvolutionSlot`); a failed evolution releases the slot in a `finally`.
- Tenant policy second. A twin with `tenantId == null` is **ungoverned by tenant policy** by design
  (legacy/unassigned), not silently denied and not given a fabricated tenant.
- Within a policy version, **first matching rule wins** (rules are appended, so this is the version's
  declared precedence). Across policies, **highest severity wins** (DENY 2 > REQUIRE_APPROVAL 1 >
  ALLOW 0), platform winning ties.
- No matching rule anywhere → **ALLOW** ("ungoverned"), explicitly chosen so policy silence never
  blocks work.
- Unknown tenant → the engine throws; `enforceTenantPolicy` catches `NoSuchElementException` /
  `PolicyEvaluationException` and returns **DENY**. Evaluation failure **fails closed**.

**Tenant isolation.** Every tenant-scoped operation takes `tenantId` and compares it
(`ApprovalService.get` uses `Objects.equals` and throws `NoSuchElementException` → HTTP 404 on
mismatch). Policy operations are tenant-scoped in `TenantPolicyService`.

> **Trust boundary — read carefully.**
> **`tenantId` is caller-supplied on every request. Authentication is NOT implemented.** Any client
> may claim any tenant. Tenant "isolation" here means *the code consistently scopes data by the
> tenantId it was given* — it is **not** a security guarantee, and must not be read as one. The
> governance UI labels this ("Acting as tenant (not authenticated)").

---

## 10. Approval State Machine

```
                 REQUIRE_APPROVAL
                        │
                        ▼
                    PENDING ──── rejectApproval ───► REJECTED   (governed action never runs)
                        │
                 approveEvolution
                        │
                        ▼
                    APPROVED ───► executeAfterGovernance
                                        ├── success ──► COMPLETED
                                        └── failure ──► FAILED
```

- **Persistence:** `ApprovalStore` (`approvals.json`), whole-list rewrite, atomic tmp-then-move.
- **Immutability:** `Approval` is a record; transitions replace the map entry via `withStatus(...)`.
  `ApprovalService` has **no removal path** — statuses change, records are never deleted.
- **Tenant isolation:** every read/transition goes through `get(approvalId, tenantId)`.
- **Pinned policy context:** `policyId`, `policyVersionId`, `policyVersionNumber`, `matchedRuleId`,
  `reason` are captured at creation. Resolving an approval **never** calls `PolicyDecisionEngine`
  again, so activating a new policy version afterwards cannot retroactively change what a pending
  approval means.
- **Idempotency / authoritative evidence:** reconciliation reads the `evolvedAgent_*` variable from
  Camunda's committed history — absence proves the side effect never ran; presence proves it did —
  rather than trusting the workbench's own bookkeeping.
- **Restart reconciliation** (`reconcileApprovedApprovals`, for `APPROVED` only): twin gone → FAILED;
  variable already set → COMPLETED (not re-run); otherwise run it now as its first real execution,
  releasing the platform slot on failure.
- **Current limitations:** `PENDING` approvals are *not* reconciled at startup — they simply remain
  pending, and approving one whose twin has vanished returns a FAILED decision with a clear reason.
  Approvals carry no `modelId`; they are twin-scoped and listed per tenant.

---

## 11. Persistence / Restart

| Store | Owns | Does **not** own | Write | Load |
|---|---|---|---|---|
| `WorkbenchStateStore` | ProcessModels, TwinProcesses | workflow history, approvals, projects | whole-file atomic rewrite after each change | `restoreState()` |
| `WorkflowEventStore` | per-model stage events | current state (that's a fold) | whole-file atomic rewrite per `record()` | tracker `@PostConstruct` |
| `ApprovalStore` | approvals | policies, twins | whole-list atomic rewrite | `ApprovalService` init |
| `TenantPolicyStore` | tenants, policies, versions, rules | approvals, models | whole-snapshot atomic rewrite | `TenantPolicyService` init |
| `ProcessModelFileStore` | `data/models/{id}.bpmn` | in-memory model | atomic tmp-then-move; write failures **throw**, delete failures only log | on demand |
| generated-projects FS | project identity, directory, process key | which model owns it | `generate()` | `scanExisting()` |
| H2 (Camunda) | deployments, instances, variables, history | anything MetaML-specific | engine | engine |

All JSON stores share the same shape: whole-file rewrite, tmp-then-atomic-move, never throw to the
caller on write failure, and an `enabled` flag used only by tests.

```
 Spring startup
   │
   ├─ WorkflowEventStore.load()   →  WorkflowStateTracker.restore()   (@PostConstruct, runs first)
   ├─ TenantPolicyStore.load(), ApprovalStore.load()
   │
   └─ WorkbenchServiceImpl.restoreState()      @PostConstruct
        ├─ models + twins ← WorkbenchStateStore.load()
        ├─ backfill MODEL/COMPLETED ONLY for a model with NO history at all
        ├─ restoreGeneratedProjects()
        │     ├─ generatedProjects ← scanExisting()      (directory is source of truth)
        │     ├─ modelIdByProjectId ← latest GENERATE/COMPLETED, if that dir still exists
        │     ├─ hard-kill guard: recorded LAUNCH port still listening → skip this model
        │     └─ else collect superseded generations
        └─ reconcileApprovedApprovals()
```

Spring guarantees a dependency bean is fully constructed (including `@PostConstruct`) before
injection, which is what makes the tracker's history reliably available inside `restoreState()`.

**Intentionally runtime-only:** generated JVM registry, platform quota counters and policy
overrides, in-flight evolution claims, all locks.

---

## 12. Model Deletion Contract

```
DELETED                                  PRESERVED
────────────────────────────             ──────────────────────────────────
ProcessModel record                      TwinProcess (all of them)
data/models/{id}.bpmn                    Camunda deployments
ALL generated project dirs               Camunda process instances (original + twin)
generatedProjects entries                approvals (every status)
modelIdByProjectId entries               tenants / policies / versions / rules
                                         workflow history (never pruned)
```

**Refuses** (HTTP 409, nothing mutated) if any of the model's generated apps is running or
mid-launch. Deleting a model never kills a running application.

**Why twins survive.** Model → Twin is provenance, not ownership (§8). A twin needs only Camunda ids
to keep working, so tearing it down would destroy functioning runtime state for a bookkeeping reason.

**Why Camunda survives.** Twin deployments are deduplicated per model and therefore **shared across
all twins of that model**, and `deleteDeployment(id, true)` cascades to live process instances — the
codebase already guards against this in `launchProcess`. Deleting deployments would silently
terminate healthy twins.

**Why history survives — and why the ID is retired.** History is retained, so recreating a deleted id
would let the new incarnation inherit the dead model's `GENERATE`/`LAUNCH` events;
`currentProjectIdOf` would then report a previous incarnation's project as the new model's current
generation. Retention of history therefore *requires* permanent ID retirement. Attempting reuse →
`IllegalArgumentException` → **HTTP 400**, "…has already been used and cannot be reused".

**UI when a twin references a deleted model.** `EvolvePage.loadModelDiagram` distinguishes 404 from
other failures. On 404 the page clears the model-id input (so a dead id is never carried into a new
deploy) and states: *"Twin … is still running, but the model it was created from (…) has been
deleted, so its diagram can't be shown. Evolve and Bridge still work on this twin."* The event log
still loads and Connect/Evolve/Bridge still function. `DeployedAppsPage` renders "(model unknown)"
instead of an "Evolve this" link when the mapping is gone.

---

## 13. Generated BPMN Applications

- **Discovery:** `model.getModelElementsByType(Activity.class)` — queried through the BPMN supertype,
  so an element type nobody enumerated is still classified by the rule rather than vanishing.
  Document order; deterministic across runs.
- **Classification:** by execution semantics, not element type — eligible ⇔ *the engine parks the
  token and will never move it on its own*. `camunda:type="external"` is checked first and overrides
  the element type (an external `serviceTask` is a wait state; a `serviceTask` with a
  `delegateExpression` is not eligible). Sub-processes/call activities are containers; their
  wait-state children qualify on their own merits.
- **Endpoints:** `POST /api/v1/process/{processInstanceId}/{slug}/complete`, one per eligible
  activity, plus `POST /api/v1/process/start`. Slug is presentation only; the **activity id is
  emitted as a literal**, so an endpoint cannot reach a different activity however the slug was
  derived. Slug collisions are disambiguated in document order (two endpoints on one path would be a
  startup failure in the generated app).
- **Generation:** copy the `templates/camundademo` project, delete its placeholder demo content
  (`loanApproval.bpmn`, `CalculateInterestService`, `Camundacontroller`, `LoanApplicationContext`,
  `BPMNProcessRESTMappings`), write the real BPMN under `src/main/resources/processes/{key}.bpmn`,
  write each generated delegate into `com.example.camundademo.delegates` (package must match, or
  component scan silently misses it), and write `GeneratedProcessController`.
  Only the helper methods actually used are emitted; `ExternalTaskService` is injected only when an
  external task exists.
- **Lifecycle:** started via the project's own `mvnw`/`mvnw.cmd` (absolute path) with `SERVER_PORT`
  and `SERVER_ADDRESS=127.0.0.1` (loopback-bound — verified, since Spring binds all interfaces by
  default); readiness by polling the port while also checking `isAlive()`, 5-minute timeout; stop
  walks `Process.descendants()` because on Windows the immediate child is `cmd.exe`.

**Boundaries.** Generated applications are **standalone** — their own JVM, own embedded Camunda, own
H2, zero coupling back to MetaML and no governance inside them. MetaML knows only projectId,
directory, process key, port, and OS process handle. **Generated delegates are scaffolding**:
`DelegateClassGenerator` emits `// TODO: implement <name>` bodies for the user to fill in.

---

## 14. Frontend Architecture

React + react-bootstrap + bpmn.io. `frontend/src/services/workbench/WorkbenchService.js` is the
single API wrapper (axios, `baseURL: http://localhost:8082/api/v1`).

| Page | Route | Responsibility |
|---|---|---|
| `ModelPage` | `/wb/model/new`, `/wb/model/:id` | bpmn.io editor; Save (with tenant selector), Generate, Launch; workflow breadcrumb polled while a stage is `IN_PROGRESS`; restores `projectId` from the backend's `GENERATE` detail so Launch survives a reload |
| `EditProjectListPage` | `/wb/model/edit` | lists saved models; **Open** and **Delete** (reuses the existing `DeleteConfirmationModal`; a 409 renders as a dismissible warning, list intact) |
| `EvolvePage` | `/wb/evolve` | Deploy+Twin, Connect, Evolve, Bridge, Complete task(s), twin event log, canvas selection, platform policy view/update + per-twin usage |
| `DeployedAppsPage` | `/wb/deployed` | running generated apps (from the live registry); Stop; "Evolve this" → model editor, or "(model unknown)" |
| `GovernancePoliciesPage` | `/wb/governance/policies` | tenant → policy → draft version → rules → activate |
| `GovernanceApprovalsPage` | `/wb/governance/approvals` | tenant-scoped approval list; approve/reject via `ApprovalActionConfirmationModal` |

**Contracts.** All frontend calls map to real backend routes. Status mapping:
`IllegalArgumentException` → 400, `NoSuchElementException` → 404, `IllegalStateException` → 409,
`NodeManagerUnavailableException` → 503, otherwise 500. `stop-project` returns 404 when nothing was
running (body still carries the flag).

**No false success.** Evolve/Bridge return HTTP 200 with `approved:false` for DENY /
REQUIRE_APPROVAL; `EvolvePage` branches on `decision.approved` and renders the backend's reason as
*blocked*, with "already forwarded" treated as informational rather than an error.

---

## 15. Test Architecture

**Modules:** `workbench` (148) — unit + service-level with real generator/launcher/tracker and mocked
Camunda/governance; `wbapi` (76) — full `@SpringBootTest` walkthroughs against a real engine;
`nodemanager` (6). **Total 230, 0 failures.**

**`@IsolatedWorkbenchTest`** (`wbapi/src/test/java/com/metaml/wbapi/IsolatedWorkbenchTest.java`) is a
meta-annotation carrying `@SpringBootTest` with all seven isolation properties:

```
workbench.state.persist=false                     workbench.workflow.persist=false
workbench.governance.approval.persist=false       workbench.governance.tenant-policy.persist=false
workbench.models.directory=./target/test-data/models
workbench.generation.output-directory=./target/test-data/generated-projects
workbench.generation.template-directory=../../templates/camundademo   (read-only input)
```

Test classes add only their own bits (`spring.datasource.url`, Camunda timings) via
`@TestPropertySource`, which takes precedence. **Every store carries its own persist flag and its own
path**; each one left at its production default writes into `./data/`. Because the complete set is
declared once, a new test cannot reintroduce the leak by forgetting a line.

**Intentionally persistent tests** construct stores directly against a per-test `@TempDir` with
`enabled = true` — the workflow-restart tests and the three approval-restart tests in
`WireTransferWalkthroughTest`, and `TenantPolicyServiceTest`'s restart case. These bypass the Spring
beans entirely, so they exercise real persistence against their own controlled files and are
unaffected by the annotation.

**Live-verification strategy:** the launcher tests stand in a fake `mvnw.cmd` that opens a raw TCP
listener on `SERVER_PORT`, so real process/port/liveness behaviour is exercised without booting a
Spring Boot app per test. Manual live verification runs the real backend against a disposable data
directory (every store overridden to a scratch path) so real development data is never mutated.

---

## 16. Live Verification History

Performed against a running backend (real Camunda, real generated apps), using disposable data
directories:

- **Governance:** ALLOW, DENY, REQUIRE_APPROVAL each reached through the real Evolve path; tenant →
  policy → version → rule → activate driven through the REST API; approval created by a real
  `REQUIRE_APPROVAL` decision; tenant isolation and tenant ownership (Model → Twin → Evolve).
- **Approvals:** approve/reject/history through the UI; approval survived a real backend restart.
- **Generated apps:** launched real Spring Boot + Camunda children; killed one via OS `taskkill` and
  confirmed it stopped being reported as running; relaunch under the same id.
- **Retention:** generate P1 → P2 collected P1; with P1 *running*, P2's generation retained P1 intact
  and serving traffic; stopping P1 collected it; an externally-killed superseded project was
  collected at the next lifecycle event; restart collected a project superseded while running.
- **Model deletion:** unlaunched model deleted (model, `.bpmn`, project gone; history retained);
  delete refused with 409 while the app ran and the app kept serving; succeeded after stop; a twin
  survived its model's deletion and its Camunda runtime accepted a real task completion; a PENDING
  approval was untouched; deleted id rejected on recreate; new id worked; all of it held across a
  restart. Delete also driven end-to-end through the UI.
- **Hard-kill protection:** a live listener on a recorded launch port plus a fresh launcher (empty
  registry) across a restart — cleanup deferred, then resumed once the port was free.
- **Test-store isolation:** all four real data files verified byte-identical (md5) across full suite
  runs.

---

## 17. Intentional Limitations

1. **No authentication.** `tenantId` is caller-supplied; the trust boundary is documented and labelled
   in the UI, not enforced.
2. **Platform governance is runtime-only.** Limits and per-twin counters reset on restart — an
   explicit, tested contract, distinct from durable tenant governance.
3. **Generated applications are standalone.** No callback, no shared state, no governance inside them.
4. **Generated delegates are scaffolding** (`// TODO: implement`) for the user to complete.
5. **Model deletion is irreversible** — no archive, no undo, and the id is permanently unavailable.
6. **Deletion refuses rather than stops** a running generated app.
7. **Twins outlive their models**, leaving `TwinProcess.modelId` dangling by design.
8. **Workflow history is never pruned** by any product operation.
9. **`PENDING` approvals are not reconciled at startup** (only `APPROVED` ones are).

---

## 18. Non-Blocking Technical Debt

1. **Hard-kill guard cannot see an unrecorded port.** If a leaked generated JVM's launch port was
   never written to `LAUNCH` history, the guard has nothing to probe.
2. **Platform quota counter maps are never pruned.** They grow in step with `twinProcesses` (itself
   unbounded and persisted) and there is no twin-removal operation to hook cleanup onto.
3. **Loop-back re-entry of a plain activity by two genuinely concurrent tokens** is not
   disambiguated by the "already forwarded" ordinal-position check — a known, narrow residual gap
   documented at the call site.
4. **Dev-data backup artifacts** from one-time cleanups remain in `backend/wbapi/data/`
   (`*.backup-*`, `unclaimed-projects-backup-*`). Inert; no code reads them.

---

## 19. Architectural Invariants

1. Tenant ownership flows **Model → Twin → Evolve**; a twin inherits `tenantId` at launch and it is
   never reassigned.
2. **Every** real Evolve/Bridge path (manual evolve, manual bridge, both bridge overloads,
   `AutoBridgeTrigger`) funnels through `runEvolution` → platform quota → tenant policy.
   `executeAfterGovernance` is reachable only post-governance or post-approval.
3. Tenant policy evaluation **fails closed**: an unknown tenant or malformed rule yields DENY.
4. Platform severity beats tenant on ties; no matching rule anywhere → ALLOW (ungoverned).
5. `REQUIRE_APPROVAL` **pins** its originating policy decision; resolution never re-evaluates.
6. The `evolvedAgent_*` Camunda variable is the single authoritative record that an evolution's side
   effect occurred, and the basis for both duplicate suppression and restart reconciliation.
7. Model deletion **never** terminates twins, Camunda instances, or deployments, and never removes
   approvals, tenants, or workflow history.
8. Generated-project deletion never targets a current generation, a running project, an in-flight
   launch, another model's project, or a path outside the output directory.
9. A model id that once reached `MODEL/COMPLETED` and has no current model is **permanently retired**;
   a *failed* creation does not retire its id.
10. Workflow history is historical state: read-only to the product, never pruned, and the basis for
    both "current generation" and id retirement.
11. The generated-JVM registry, platform quota state, in-flight claims and all locks are
    **runtime-only** and intentionally do not survive restart.
12. `generatedProjects` and `modelIdByProjectId` are **derived** and fully reconstructed at startup.
13. Test infrastructure cannot write to real development persistence.

---

## 20. Current Completion Status

**COMPLETE**, meaning:

- every known implementation loose end from this effort has been closed or explicitly classified as
  an intentional limitation;
- the full backend suite passes (**230 tests, 0 failures**);
- all accepted limitations are stated in §17 rather than left implicit;
- no unresolved product decision remains from this implementation cycle.

**This does not claim the system is bug-free**, secure, or production-ready. It has no
authentication, its trust boundary is explicitly unenforced, and it has not been reviewed
independently — which is what this handoff is for.

---

## 21. What Codex Should Independently Challenge

- **State ownership.** Is the authoritative/derived/runtime-only split in §5 actually true in the
  code? Can any derived structure diverge from its source in a way that survives a restart?
- **Restart reconstruction.** `restoreGeneratedProjects` + `reconcileApprovedApprovals` +
  `WorkflowStateTracker.restore` ordering, and the `MODEL/COMPLETED` backfill condition.
- **Deletion semantics.** Is the model-delete cascade exactly the documented set? Any partially
  mutating path if a step fails mid-way?
- **Generated-project cleanup.** `runIfAllIdle` lock ordering and `tryLock` failure handling; the
  current-generation re-check inside the guarded action; path containment in `delete()`.
- **Liveness.** `Process.isAlive()` self-heal and its conditional removal; the restart-only port
  probe — is fail-closed actually fail-closed, and does it ever run outside startup?
- **Governance bypasses.** Any path reaching `executeAfterGovernance`, `setVariable` on a twin, or the
  node manager without passing `runEvolution`. Platform-vs-tenant ordering and tie-breaking.
- **Approval idempotency.** Reconciliation's use of the `evolvedAgent_*` variable; the
  `loopCounter`-present vs absent branches in `alreadyEvolved`; slot reserve/release pairing.
- **Tenant isolation.** Every tenant-scoped read/write; whether any endpoint leaks across tenants
  given that `tenantId` is unauthenticated input.
- **ID retirement.** Whether `MODEL/COMPLETED` is the right key, and whether any path can resurrect
  history onto a new incarnation.
- **Concurrency.** Model lock vs project locks vs `evolutionsInFlight` claims vs the twin monitor —
  ordering, reentrancy, and anything that can deadlock or interleave incorrectly.
- **Frontend/backend parity.** Whether any UI action can report success the backend did not perform,
  or display materially stale runtime state.
- **Generated-app boundary.** Any assumption MetaML makes about a generated app's identity,
  lifecycle, or endpoints that the process boundary does not actually support.
