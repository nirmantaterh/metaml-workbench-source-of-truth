# MetaML Workbench — Tasks 1–3 Demo Run

A read-through script for presenting to your teammates. Each step has what to click, what to say out loud, and what the result should look like — including gotchas that already tripped up a real run.

---

## 0. Before anyone's watching

- [ ] Node manager running on `8083` — terminal 1: `cd backend && .\mvnw.cmd -pl nodemanager spring-boot:run`
- [ ] Workbench backend running on `8082` — terminal 2: `cd backend && .\mvnw.cmd -pl workbench install -DskipTests` then `.\mvnw.cmd -pl wbapi spring-boot:run`
- [ ] Frontend running on `3000` — terminal 3: `cd frontend && npm start`
- [ ] Browser tab open at `localhost:3000/wb/transmute/demo`, zoomed so the "Element properties" panel is visible without scrolling
- [ ] Card 6 → click **View policy** → click **Update policy** with the denylist field cleared and quota set to `3`, so you're starting from a known state regardless of what earlier testing left behind

> **SAY:** "Hey everyone — today I'm walking through Tasks 1 through 3 of the MetaML workbench: modeling a process, deploying it with a twin, letting the two communicate automatically, requesting an agent through the node manager, and enforcing governance on top of that. Everything you'll see is live — real Camunda engine, real HTTP calls, nothing mocked."

---

## 1. Task 1 — model, deploy, twin, connect, communicate

Card 1 has the editor. Cards 2–4 handle load, launch, and connect. Card 5b is the automatic communication piece.

### 1.1 — Select the task
**DO:** Click the `Review Application` task on the canvas.

> **SAY:** "This is a real bpmn-js editor — the same library Camunda's own Modeler uses. The process is Start → a User Task → End. Let me fill in some metadata on this task."

### 1.2 — Fill in metadata
**DO:** In the right panel, set `Assignee / owner` → `loan-officer`, `Input data` → `applicantId`, `Output data` → `reviewDecision`, `Notes` → anything. Tab out of each field.

> **SAY:** "This isn't a side database — assignee, input, output, and notes get written directly into the BPMN XML as real Camunda properties: `camunda:assignee`, `camunda:inputOutput`, `camunda:properties`. Notice the assignee field only shows up because this is a User Task — Camunda's own schema doesn't allow it anywhere else, so we don't let you set it anywhere else either."

### 1.3 — Save (deploy)
**DO:** Card 1 → click **Save model (exports real BPMN XML)**.

> **SAY:** "That just deployed this diagram to a real Camunda engine."

✅ **WATCH FOR:** a `processDefinitionId` in the result — that's Camunda's own confirmation the XML was valid and deployed, not just saved.

### 1.4 — Load, confirm round-trip
**DO:** Card 2 → the model id is already filled in → click **Load model**, then reselect the task.

> **SAY:** "And the metadata's still there — round-tripped straight through the XML, no separate record to keep in sync."

### 1.5 — Launch + connect
**DO:** Card 3 → click **Launch**. Card 4 → ids already default to `Activity_Review` / `Activity_Review` → click **Connect**.

> **SAY:** "Launch starts two real Camunda process instances from that same definition — the original process and a twin. Connect links an activity in the original to its counterpart in the twin, which is what lets the twin later act on behalf of that activity."

### 1.6 — Bridge: automatic communication (do this before any manual Evolve on this twin)
**DO:** Card 5b → click **Check "Activity_Review" in original process and auto-forward to twin**.

> **SAY:** "This is the piece that actually satisfies 'the twin process activities communicate' — no human picked an agent here. It reads Camunda's own execution history for the original process, sees that Activity_Review was genuinely reached, and automatically forwards that event to the twin through the same governance and node-manager path a manual request would use."

✅ **WATCH FOR (approved):** `"approved": true`, agent name `validator-agent-01`.

⚠️ **GOTCHA — order matters here.** Once *any* successful evolution (manual or via Bridge) has happened for a twin+activity, a later Bridge call for that same pair correctly refuses to run again — `"reason": "Activity event already forwarded to twin"`. That's not a bug, it's the fix that stops the automatic path from silently overwriting a result. It just means: to demo Bridge actually forwarding something, do it on a fresh twin *before* touching Card 5's manual Evolve buttons — which is exactly the order this script follows. If you deviate and test manual Evolve first, either click **Launch** again for a fresh twin, or expect the "already forwarded" response and explain why.

---

## 2. Task 2 — node manager

No dedicated screen for this — it's the layer Task 3 sits in front of, so it's easiest to show as part of the evolve calls above and below.

> **SAY:** "Task 2 is the node manager — a separate Spring Boot server on port 8083 with its own catalog of agent types. Both the manual Evolve buttons and the Bridge you just saw make a real HTTP call out to that server asking 'is this agent type available?' You'll see that call happen in the event log."

---

## 3. Task 3 — governance, layered in front

Card 5 evolves manually. Card 6 is governance. This is the part worth slowing down for.

**Quota note:** Bridge (step 1.6) already used one of this twin's 3 evolution slots. The steps below account for that — don't add an extra manual evolve beyond what's written or you'll hit the quota block a step early.

### 3.1 — Evolve an allowed agent, manually
**DO:** Card 5 → Agent type is already `validator` → click **Evolve (try this agent type)**.

> **SAY:** "Same activity, but now a human is explicitly requesting it instead of the bridge. Validator is a real entry in the node manager's catalog, and governance has nothing against it, so this goes through too — and notice it's allowed to run even though Bridge already handled this activity. A human's explicit choice is always allowed to proceed."

✅ **WATCH FOR (approved):** `"approved": true`, `agentName: validator-agent-01`. Quota is now 2 of 3 for this twin.

⚠️ **GOTCHA:** The outer `"message": "Success!"` just means the API call didn't error — it says nothing about approval. The real answer is always `data.approved` and `data.reason`. This tripped up a real run of this exact demo.

### 3.2 — Set a governance denylist
**DO:** Card 6 → type `rogue-agent` into **Denied agent types**, leave quota at `3`, click **Update policy**.

> **SAY:** "This is a governance-only rule — the node manager knows nothing about it. I'm about to block an agent type the node manager was never even asked about."

### 3.3 — Evolve the denied agent
**DO:** Card 5 → click **Evolve with "rogue-agent" (expect blocked)**.

🚫 **WATCH FOR (blocked by governance):** `"approved": false`, reason `"Agent type 'rogue-agent' is denied by governance policy"`.

> **SAY:** "That message is the tell — it says *governance*, not 'not found in catalog.' The node manager was never even contacted; governance stopped it first." Quota is unaffected by a denylist block — still 2 of 3.

### 3.4 — Use up the quota
**DO:** Card 5 → agent type back to `validator` → click **Evolve** one more time.

> **SAY:** "Quota's set to 3 for this twin. Bridge used one slot, my manual evolve used a second — one more should still go through, putting us at 3 of 3."

✅ **WATCH FOR (approved):** quota is now 3 of 3.

### 3.5 — Hit the quota block
**DO:** Click **Evolve** one more time.

🚫 **WATCH FOR (blocked by governance):** reason `"Evolution quota exceeded for twin process ... (3/3)"`.

> **SAY:** "Same twin, same allowed agent type — but it's used its quota, so governance shuts it down regardless of what the node manager would say. Card 6's 'View evolution usage for this twin' shows the same 3-of-3 count live, if you want to point at it directly."

---

## 4. The one artifact that proves the whole chain

### 4.1 — Show the event log
**DO:** Open a new tab, paste: `localhost:8082/api/v1/wb/transmute/twin/<twinId>`

> **SAY:** "This is the twin process's full event log — every stage, in order: the definition deploying, both process instances starting, the connect, the automatic bridge forward, then every manual evolve attempt with exactly why it was approved or blocked. If anyone asks 'how do I know governance actually ran before the node manager,' this is the receipt."

---

## Troubleshooting — if something looks wrong mid-demo

**"Success!" but I expected a block**
Not a bug — read `data.approved`/`data.reason`, not the outer `message`. See the gotcha in step 3.1.

**Bridge says "already forwarded to twin" instead of actually forwarding**
Not a bug — some evolution (manual or bridge) already succeeded for that twin+activity. Either point at it as proof the idempotency fix works, or click **Launch** for a fresh twin and try Bridge again before touching Card 5.

**Evolve says "not found in node manager catalog" instead of a governance message**
The denylist update in step 3.2 didn't take — click Card 6's "View policy" to confirm `rogue-agent` is actually listed, then retry.

**Quota block shows up earlier or later than expected**
Every **Launch** creates a brand-new twin with its own quota counter starting at zero. Remember Bridge (step 1.6) uses one slot before Task 3 even starts — see the quota note at the top of section 3. If the count still looks off, you're probably reusing an older twin id; launch a fresh one and it resets.

---

## Handing this off to a teammate

Once pushed, any teammate who already has the repo cloned just runs:

```
git pull origin master
```

— then follows the setup checklist at the top of this page. No separate install steps beyond the usual `npm install` / Maven build.
