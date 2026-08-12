# MetaML Workbench — Project Status

**Last updated:** 2026-08-12
**Purpose:** hand this file to a fresh Claude Code session so it can pick up cold, with zero prior chat history. Say: *"Read PROJECT_STATUS.md and continue from there."*

---

## 1. What this project is

MetaML Workbench: a Camunda/Spring Boot BPMN platform. Core pipeline:

```
Model (BPMN)  →  Generate (delegates + Spring Boot project)  →  Launch (real child JVM)
     ↓
   Twin (shadow process instance, mirrors the original)
     ↓
Connect / Evolve / Bridge  →  Governance  →  real side effect
```

## 2. Current architecture (as of this file)

```
                              MetaML Workbench
                                     │
        ┌────────────────────────────┼─────────────────────────────┐
        │                            │                              │
    Model → Generate → Launch      Evolve                     Governance
   (BPMN → Delegates →          (Twin workflow:            Tenant → Policy →
    Spring Boot → run)         Connect/Bridge/Evolve)      Version → Rule →
        │                            │                     PolicyDecisionEngine
        │                            ▼                              │
        │                     runEvolution()               ┌───────┴────────┐
        │                            │                   ALLOW/DENY/REQUIRE_APPROVAL
        │              ┌─────────────┼─────────────┐               │
        │        platform quota   tenant policy   [null tenant  Approval
        │        (GovernanceService) (if twin has  = ungoverned,  PENDING
        │                            a tenant)      by design]      │
        │                            │                        ┌─────┴─────┐
        │                            ▼                        ▼           ▼
        │                     PolicyDecisionEngine        APPROVE      REJECT
        │                                                      │           │
        │                                                      ▼           ▼
        │                                                  execute     REJECTED
        │                                                      │
        │                                              COMPLETED/FAILED
        ▼
  generated Spring Boot app (separate JVM, zero coupling to governance,
  per-BPMN-activity REST endpoints, e.g. POST /{id}/review/complete)
```

**Persistence (all file-backed, atomic write, survive real restart):**
- `WorkbenchStateStore` → models, twins
- `TenantPolicyStore` → tenants, policies, versions, rules
- `ApprovalStore` → approvals
- `WorkflowEventStore` → Model→Generate→Launch pipeline history
- Generated-project registry → **reconstructed at startup** from the project directory + `WorkflowEventStore`, not its own file (see Phase list below)
- Camunda's own process state → `jdbc:h2:file:./data/camunda` (file-based, genuinely durable)

**Trust boundary, unchanged since day one:** `tenantId` is caller-supplied, never authenticated. Every governance UI explicitly labels this ("Acting as tenant (not authenticated)").

## 3. What's been done this session (chronological, all verified live + tested)

1. **Governance frontend audit + Approvals UI** — built `GovernanceApprovalsPage.js` on the existing backend (Policy/Approval endpoints already existed). Live-verified approve/reject/history/tenant-isolation through the real UI.
2. **BPMN activity → REST endpoint generation, generalized** — moved from "one endpoint per user task" to "one endpoint per activity whose execution semantics require external triggering" (`BpmnActivities.Trigger`: `USER_TASK`, `RECEIVE_TASK`, `EXTERNAL_TASK`). File: `backend/workbench/.../generation/BpmnActivities.java` (new), `SpringBootProjectGenerator.java`.
3. **Tenant ownership wired into normal model creation** — `ModelPage.js` got a tenant selector; `saveModel` now sends `tenantId` (or `null`, never `""`). Full chain UI → `ProcessModel.tenantId` → `TwinProcess.tenantId` → governance verified live for ALLOW/DENY/REQUIRE_APPROVAL + tenant isolation.
4. **Generated-project restart persistence** — root cause: `generatedProjects`/`modelIdByProjectId` in `WorkbenchServiceImpl` were in-memory only. Fixed by **reconstruction, not a new store**: `SpringBootProjectGenerator.scanExisting()` rebuilds from the project directory itself; `modelIdByProjectId` rebuilds from `WorkflowEventStore`'s already-persisted `GENERATE` stage detail. Real restart + relaunch verified live.
5. **Element-aware generation diagnostics — COMPLETE, verified end-to-end (2026-08-12).** `DelegateClassGenerator.generate` now detects a **delegate class-name collision**: two distinct delegate expressions that sanitise to the same Java class name (`${settle_payment}` and `${settle-payment}` both → `Settle_payment`). It throws `InvalidDelegateExpressionException` naming the element that *loses* (the one whose bean would silently never exist at runtime). `WorkbenchServiceImpl.generateErrorFrom` maps it to a `StageError` carrying `delegateExpression` + `bpmnElementId`, which is what makes **Go to error** render and select that exact element in the live modeler. Verified live: RED → Go to error → `Task_SettleB` selected → rename → Save → Generate → GREEN, with both delegate classes written. Shared-bean models (same bean name on several elements) still correctly yield `bpmnElementId = null` and do not error; global failures still carry `null` and never fabricate an element. Fixture: `demo/settlement-collision.bpmn`. **Do not reimplement.**
   - One frontend fix was required and made: `ModelPage.handleGenerate` used to call `generateDelegates` before `generateProject`. `generateDelegates` records no workflow state, so a generation failure raised there aborted the click before the FAILED stage carrying `bpmnElementId` was ever written — the banner appeared but the breadcrumb sat on Pending and "Go to error" never showed. The preview call was redundant (its result was discarded; `doGenerateSpringBootProject` regenerates the delegates itself, against the correct package) and was removed. The `/wb/transmute/generate` endpoint and service method are untouched — no API contract change.
6. **Generated-app liveness bug, found and fixed** — `SpringBootProjectLauncher.find()`/`listRunning()` never re-checked `Process.isAlive()`, so a crashed generated app was reported "running" forever (reproduced live: killed a real JVM via OS `taskkill`, confirmed MetaML still said it was running). Fixed with query-time `isAlive()` checks + self-healing registry removal. No new daemon/scheduler. Live-verified: dead project disappears from `running-projects`, unrelated live app unaffected, relaunch under the same id works.

## 3b. Demo fixture status

`demo/` carries four fixtures, all current: `wire-transfer-review.bpmn` (canonical Model → Generate
→ Launch), `wire-transfer-review-twin.bpmn` (Twin / Connect / Evolve / Bridge / governance),
`wire-transfer-review-BROKEN.bpmn` (real save-time rejection, 400), and
`settlement-collision.bpmn` (element-specific generation failure → Go to error → recovery).
`demo/DEMO_PROTOCOL.md` is the demo + regression script and is current as of 2026-08-12 — its old
"Known limitation — Go to error" section, which claimed the loop could not be demonstrated from a
BPMN fixture, was **wrong after the collision work** and has been replaced with the verified flow.

## 4. Uncommitted files (nothing has been pushed since commit `d952feb`)

**Backend:**
```
M  backend/wbapi/.../controller/workbench/WorkbenchController.java
M  backend/wbapi/.../payload/request/SaveProcessModelRequest.java
M  backend/wbapi/.../utils/WorkbenchUrlMapping.java
M  backend/wbapi/.../test/.../WireTransferWalkthroughTest.java
M  backend/workbench/.../generation/SpringBootProjectGenerator.java
M  backend/workbench/.../generation/SpringBootProjectLauncher.java
M  backend/workbench/.../model/AgentDecision.java
M  backend/workbench/.../service/WorkbenchService.java
M  backend/workbench/.../service/WorkbenchServiceImpl.java
M  backend/workbench/.../test/.../SpringBootProjectGeneratorTest.java
M  backend/workbench/.../test/.../SpringBootProjectLauncherTest.java
?? backend/wbapi/.../payload/request/ResolveApprovalRequest.java
?? backend/workbench/.../generation/BpmnActivities.java
?? backend/workbench/.../governance/Approval.java
?? backend/workbench/.../governance/ApprovalService.java
?? backend/workbench/.../governance/ApprovalStatus.java
?? backend/workbench/.../governance/ApprovalStore.java
```

**Frontend:**
```
M  frontend/src/Navigation.js
M  frontend/src/components/toolbars/Header.js
M  frontend/src/pages/workbench/ModelPage.js
M  frontend/src/routes.js
M  frontend/src/services/workbench/WorkbenchService.js
?? frontend/src/components/modals/ApprovalActionConfirmationModal.js
?? frontend/src/pages/workbench/GovernanceApprovalsPage.js
?? frontend/src/pages/workbench/GovernancePoliciesPage.js
```

Since that list was written, the element-aware diagnostics work additionally touched:

```
M  backend/workbench/.../codegen/DelegateClassGenerator.java        (collision check)
M  backend/workbench/.../test/.../DelegateClassGeneratorTest.java   (14 tests, was 13)
?? backend/workbench/.../codegen/InvalidDelegateExpressionException.java
M  frontend/src/pages/workbench/ModelPage.js                        (dropped redundant generateDelegates call)
?? demo/settlement-collision.bpmn
M  demo/DEMO_PROTOCOL.md
```

Re-run `git status --short backend/ frontend/ demo/` in a fresh session to confirm this is still current before trusting it.

## 5. Test status (last confirmed)

Full backend suite: **234 tests, 0 failures** (152 `workbench`, 76 `wbapi`, 6 `nodemanager`), measured 2026-08-12.
Real dev data unchanged by a full run: 70 models, 33 twins, 70 workflow histories, 0 orphaned histories.
Frontend: no new automated tests added (existing baseline lint/build failures are pre-existing, unrelated — confirmed via `git stash` comparison in an earlier phase).

## 6. Known limitations (evidence-backed, not fixed, not urgent)

- Generated-project directories are never deleted — unbounded disk growth (~68 dirs / 8M at last check). No cleanup mechanism exists. Explicitly deferred, not this session's scope.
- `stopGeneratedProject` on an already-externally-dead project now correctly returns 404/false instead of a misleading 200/true (an intended side effect of the liveness fix).
- No authentication anywhere — `tenantId` remains caller-supplied by design; this is documented everywhere it matters, not a bug.

## 7. How to resume in a fresh session

1. Open a new Claude Code session in this repo.
2. Say: *"Read PROJECT_STATUS.md and continue from there"* (or paste the specific next task).
3. If backend verification is needed, the standard restart sequence used throughout this project is: `cd backend && ./mvnw -pl workbench install -DskipTests` (if workbench module changed) then `nohup ./mvnw -pl wbapi spring-boot:run &`, poll `http://localhost:8082/api/v1/governance/tenants` until 200.
4. Update this file (Section 3 + 4 + 5) at the end of the next meaningful chunk of work, so the next handoff stays accurate.

**When starting a new chunk of unrelated work, update this file first**, then start the fresh session — don't let it go stale.
