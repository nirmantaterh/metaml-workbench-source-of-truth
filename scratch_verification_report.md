# MetaML Workbench — Scope Items 2/3/4 Implementation Status

**Repo:** `metaml-workbench-source-of-truth` (local path `C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth`)
**Branch:** `master`
**Date:** 2026-08-07

This is a technical status report meant to be independently checkable against the repo — every claim below points at a specific file, commit, test, or command. Nothing here is asserted without a way to verify it.

---

## Commits

```
dca7c4f  save a real .bpmn file to the server filesystem, not just Camunda's own deployment blob
9fdf9e8  generate a real Java Delegate class per delegateExpression instead of hand-writing them
4653cfc  Generate a real Spring Boot project from a saved model (scope item 4)
994886c  Auto-launch a generated Spring Boot project (scope item 4, last piece)
```

Run `git show --stat <hash>` on any of these for the exact file diff.

---

## Item 2 — Project Saving

**Claim:** Save writes a real file to the backend's filesystem; Generate is blocked until a model is saved; no client-side download happens.

**Where to look:**
- `backend/workbench/src/main/java/com/metaml/workbench/store/ProcessModelFileStore.java` — the file-writing component. Atomic write (tmp file + `Files.move`), configurable directory via `@Value("${workbench.models.directory:./data/models}")`.
- `backend/workbench/src/main/java/com/metaml/workbench/service/WorkbenchServiceImpl.java`, method `saveProcessModel` — calls `modelFileStore.save(modelId, bpmnXml)` after deployment succeeds, with rollback on failure.
- `frontend/src/pages/workbench/ModelPage.js` — the old Download button and `handleDownload` function are gone.

**Tests:**
- `backend/workbench/src/test/java/com/metaml/workbench/store/ProcessModelFileStoreTest.java`
- `backend/wbapi/src/test/java/com/metaml/wbapi/WireTransferWalkthroughTest.java`, test `savingAModelWritesItsBpmnAsARealFileOnTheServerFilesystem`

**Live verification performed this session (no browser involved):**
```
curl -s -X POST http://localhost:8082/api/v1/wb/transmute/model \
  -H "Content-Type: application/json" \
  -d '{"name":"Loan Approval Demo","bpmnXml":"..."}'
→ {"id":"72f85b5a-8f44-44c4-b57f-11e5a4f70880", ...}
```
File confirmed on disk immediately after, via `find`, at:
```
backend/wbapi/data/models/72f85b5a-8f44-44c4-b57f-11e5a4f70880.bpmn
```
No browser was involved in that request — proves the write is server-side, not a disguised client download.

---

## Item 3 — BPMN Processing

**Claim:** Parses a saved model's BPMN, extracts each service task's `delegateExpression`, sanitizes it into a valid Java identifier, and generates a real, compilable Java Delegate class per unique expression — the class name comes from the expression, not the task's display label.

**Where to look:**
- `backend/workbench/src/main/java/com/metaml/workbench/codegen/DelegateClassGenerator.java`
- `backend/workbench/src/main/java/com/metaml/workbench/codegen/GeneratedDelegate.java` (the return type: `record GeneratedDelegate(String beanName, String className, String taskName, String sourceCode)`)

**Tests:**
- `backend/workbench/src/test/java/com/metaml/workbench/codegen/DelegateClassGeneratorTest.java` — 7 tests, including a regression test for a real bug found empirically (an embedded newline in a task name breaking the generated comment).

**Live verification performed this session:**
```
curl -s -X POST http://localhost:8082/api/v1/wb/transmute/generate \
  -d '{"modelId":"72f85b5a-8f44-44c4-b57f-11e5a4f70880"}'
```
Returned real generated source:
```java
package com.metaml.generated.delegate;
...
@Component("calculateInterestService")
public class CalculateInterestService implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
        // TODO: implement Calculate Interest
    }
}
```
Input BPMN had `camunda:delegateExpression="${calculateInterestService}"` on a task named "Calculate Interest" — output class name and `@Component` bean name both derive from the expression, not the label, as required.

---

## Item 4 — Spring Boot Generation (file assembly + auto-launch)

**Claim:** Copies the real `camundademo` template (provided by Joanna, confirmed byte-for-byte against her screenshot), strips its own worked example, injects the saved model's real BPMN + generated delegates, writes a controller parameterized by the model's actual process key, and launches the result as its own live process on an auto-assigned port.

**Where to look:**
- `templates/camundademo/` — the real template, committed as-is (Spring Boot 4.1.0 / Camunda 7.24.0, unmodified).
- `backend/workbench/src/main/java/com/metaml/workbench/generation/SpringBootProjectGenerator.java` — file assembly.
- `backend/workbench/src/main/java/com/metaml/workbench/generation/SpringBootProjectLauncher.java` — process launch, port allocation, readiness polling, process-tree teardown.
- `backend/workbench/src/main/java/com/metaml/workbench/generation/GeneratedProject.java` / `LaunchedProject.java` — return types.

**A real bug found and fixed during this work (not hypothetical):** the generated delegate's `package` declaration (`com.metaml.generated.delegate`) didn't match the directory it was physically written into (`com/example/camundademo/delegates/`). `javac` doesn't enforce that match so it compiled clean, but Spring Boot's default component scan (rooted at `com.example.camundademo`) would never have found the bean — a silent runtime failure, not a build failure. Fixed by making `DelegateClassGenerator.generate()` take an explicit target package (`SpringBootProjectGenerator.DELEGATE_PACKAGE = "com.example.camundademo.delegates"`). See commit `4653cfc` for the fix and its regression test.

**A second real bug found and fixed:** this machine's `cmd.exe` will not resolve a bare relative `mvnw.cmd` for a `/c` invocation — reproduced directly against `cmd.exe` outside any Java code to rule out a `ProcessBuilder` issue. Fixed by using an absolute path to the wrapper script. See commit `994886c`.

**Tests:**
- `backend/workbench/src/test/java/com/metaml/workbench/generation/SpringBootProjectGeneratorTest.java` — 6 tests, synthetic fake template.
- `backend/workbench/src/test/java/com/metaml/workbench/generation/SpringBootProjectLauncherTest.java` — 6 tests, fake `mvnw.cmd` that opens a real TCP listener on the `SERVER_PORT` env var the launcher passes it (proves port allocation, readiness polling, re-launch-replaces-old, and clean process-tree teardown without needing a full Spring Boot app in the fast test suite).

**Endpoints:**
```
POST /api/v1/wb/transmute/generate-project   {modelId}    → {projectId, directory, processKey}
POST /api/v1/wb/transmute/launch-project     {projectId}  → {projectId, processKey, port, launchedAt}
GET  /api/v1/wb/transmute/running-projects                → [ {projectId, processKey, port, launchedAt}, ... ]
```

**Live, full end-to-end verification performed this session** (real backend process, real HTTP, real second application spun up — not mocked, not a unit test):

```
$ curl -s -X POST http://localhost:8082/api/v1/wb/transmute/generate-project -d '{"modelId":"72f85b5a-..."}'
{"projectId":"dd9f07ea-3230-48fb-a688-f3147d3a75fb","directory":".\\data\\generated-projects\\dd9f07ea-...","processKey":"loanApproval"}

$ find backend/wbapi/data/generated-projects/dd9f07ea-.../src/main/java/com/example/camundademo/delegates
CalculateInterestService.java   ← confirmed physically present, real generated Java delegate

$ curl -s -X POST http://localhost:8082/api/v1/wb/transmute/launch-project -d '{"projectId":"dd9f07ea-..."}'
{"projectId":"dd9f07ea-...","processKey":"loanApproval","port":51088,"launchedAt":"2026-08-07T23:44:10.15Z"}

$ curl -s http://localhost:8082/api/v1/wb/transmute/running-projects
[{"projectId":"dd9f07ea-...","processKey":"loanApproval","port":51088,...}]

$ curl -s -X POST http://localhost:51088/api/v1/process/start     ← hitting the GENERATED app's own port, not the workbench
{"processInstanceId":"4"}
```

That last call is the load-bearing proof: it's a brand-new, independently running Spring Boot + Camunda application (port 51088), answering with a genuine Camunda process instance id. This can only succeed if: the template copied correctly, the real BPMN deployed, the generated delegate bean was actually found by Spring's component scan (the exact thing the package-mismatch bug above would have broken), and the generated controller is wired to the right process key.

---

## Aggregate automated test results (this session, both affected modules)

```
workbench module: 26 tests, 0 failures, 0 errors   (5 test classes touched by this work)
wbapi module:      64 tests, 2 failures, 0 errors  (0 failures related to this work)
```

The 2 wbapi failures are `ZzRedundantBridgeAfterMiLoopEndedProbeTest` and `ZzStateStoreLostUpdateProbeTest` — pre-existing, unrelated adversarial "probe" tests that intentionally demonstrate two already-known, already-documented concurrency limitations elsewhere in the codebase (twin bridging after a multi-instance loop ends; a state-store lost-update race). Neither test touches anything in this work's diff. Verifiable: `git log -1 --format=%H -- <test file path>` predates this session's commits.

---

## Explicitly NOT done (no claim being made)

- Scope item 1 (Navigation & UI restructure) — not started.
- Scope item 5 (Evolve Workflow — connect to / modify an existing deployed app) — not started. The `running-projects` registry above is the data this needs; no UI/flow consumes it yet.
- Scope item 6 gap — twin automation still runs through one shared `DefaultProjectAutomationService` for every activity rather than one generated delegate per activity. Real and working, just generic rather than per-activity.
- The generated controller's exact API surface (`/start`, `/complete-task`) is an engineering default, not confirmed with Joanna.
