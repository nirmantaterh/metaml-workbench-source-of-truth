# MetaML Workbench — Tasks 1–3 Demo Run

A read-through script for presenting to your teammates. Each step has what to click, what to say
out loud, and what the result should look like — including gotchas that already tripped up a real
run.

The whole demo happens on **one page**: `http://localhost:3000/wb/model`. It has three toolbar
rows (model / twin / governance) and a right-hand sidebar (element details, twin event log,
governance results).

---

## 0. Before anyone's watching

- [ ] **Nothing stale on the ports.** A leftover Spring Boot process runs OLD compiled code and
      will give you a confident, wrong demo:
      ```powershell
      Get-NetTCPConnection -LocalPort 8082,8083,3000 -State Listen |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force }
      ```
- [ ] Node manager on `8083` — terminal 1: `cd backend` then `.\mvnw.cmd -pl nodemanager spring-boot:run`
- [ ] Workbench backend on `8082` — terminal 2: `cd backend` then
      `.\mvnw.cmd -pl workbench install -DskipTests` followed by `.\mvnw.cmd -pl wbapi spring-boot:run`
- [ ] Frontend on `3000` — terminal 3: `cd frontend` then `npm start`
      (port 3000 matters: it is the only origin the backend's CORS config allows)
- [ ] Browser at `http://localhost:3000/wb/model`, window wide enough that all three toolbar rows
      and the sidebar are visible without scrolling
- [ ] Governance row → clear **Denied agent types**, set **Max evolutions/twin** to `3`, click
      **Update policy**, so you start from a known state regardless of earlier testing

> **SAY:** "Today I'm walking through Tasks 1 through 3 of the MetaML workbench: modeling a
> process, deploying it with a twin, letting the two communicate automatically, requesting an agent
> through the node manager, and enforcing governance on top of that. Everything you'll see is live
> — real Camunda engine, real HTTP calls, nothing mocked."

---

## 1. Task 1 — model, deploy, twin, connect, communicate

### 1.1 — The canvas
The blank canvas is already **Start → Review Application (user task) → End**.

> **SAY:** "This is a real bpmn-js editor — the same library Camunda's own Modeler uses."

⚠️ **Why the user task matters:** a start-event-only process deploys and launches fine, but both
process instances run to completion the instant they start, so the twin is already gone before
anything can be evolved onto it. The user task is a genuine Camunda wait state — it's what keeps
the twin alive. If you draw your own process from scratch, include one.

### 1.2 — Fill in metadata
**DO:** Click the `Review Application` task. In the sidebar set **Name**, **Description**, and add
a **Data** row or two.

> **SAY:** "Name and description round-trip through the BPMN XML. The data rows are MetaML's own
> extension elements, written straight into the same file — no side database to keep in sync."

ℹ️ Select the process background instead of the task and the **Name** box greys out with a note.
That's deliberate: bpmn-js has no diagram label for a process element, so the field would silently
throw your keystrokes away. Use the toolbar's **Model name** box to name the model itself.

### 1.3 — Save
**DO:** Top row → **Save**.

✅ **WATCH FOR:** a green `Saved model "..." (id ...)`, and the id appearing in the **Model id** box.

> **SAY:** "That deployed the diagram to a real Camunda engine and gave us back an id."

ℹ️ Save twice and you get two different ids. Each save is a new immutable version — the backend
deliberately refuses to overwrite a model that twins may already be running against.

### 1.4 — Load, confirm round-trip
**DO:** The **Model id** box is already filled → click **Load**, then reselect the task.

> **SAY:** "Metadata's still there — round-tripped straight through the XML."

### 1.5 — Deploy + Twin
**DO:** Top row → **Deploy + Twin**.

✅ **WATCH FOR:** a green line naming the **original instance**, the **twin instance**, and the
**twin id** — and the twin id landing in the Twin row. The sidebar's **Twin Event Log** starts
filling in.

> **SAY:** "That starts two real Camunda process instances from the same definition — the original
> and its twin."

### 1.6 — Connect
**DO:** With `Review Application` still selected → Twin row → **Connect selected activity**.

> **SAY:** "Connect links an activity in the original to its counterpart in the twin. That's what
> lets the twin later act on behalf of that activity."

### 1.7 — Bridge: automatic communication (before any manual Evolve on this twin)
**DO:** Twin row → **Bridge selected activity**.

✅ **WATCH FOR (approved):** green, `approved → agent validator-agent-01`.

> **SAY:** "This is the piece that actually satisfies 'the twin process activities communicate' —
> no human picked an agent. It reads Camunda's own execution history for the original process, sees
> that Review Application was genuinely reached, and forwards that event to the twin through the
> same governance and node-manager path a manual request would use."

**DO (worth showing):** Click **Bridge selected activity** a second time.

ℹ️ **WATCH FOR:** a **grey, not red** line — "already forwarded to the twin earlier — no change
(this is expected on a repeat bridge)". Bridging twice is a deliberate no-op, not a failure: the
twin already has its agent from the first call. The backend reports it in the same shape as a real
denial, so the page special-cases it rather than crying wolf mid-demo.

---

## 2. Task 2 — node manager

No dedicated screen — it's the layer Task 3 sits in front of.

> **SAY:** "Task 2 is the node manager — a separate Spring Boot server on port 8083 with its own
> catalog of agent types. Both the manual Evolve and the Bridge you just saw make a real HTTP call
> out to that server asking 'is this agent type available?' You can see that call in the event log."

---

## 3. Task 3 — governance, layered in front

**Quota note:** Bridge (step 1.7) already used one of this twin's 3 evolution slots. The steps
below account for that — don't add an extra evolve or you'll hit the quota block a step early.

### 3.1 — Evolve an allowed agent, manually
**DO:** Twin row → agent type is already `validator` → **Evolve selected activity**.

✅ **WATCH FOR (approved):** green, `agent validator-agent-01`. Quota is now 2 of 3.

> **SAY:** "Same activity, but now a human is explicitly requesting it. Validator is a real entry in
> the node manager's catalog and governance has nothing against it, so it goes through — and it's
> allowed even though Bridge already handled this activity. A human's explicit choice always
> proceeds."

### 3.2 — Set a governance denylist
**DO:** Governance row → type `rogue-agent` into **Denied agent types**, leave quota at `3`, click
**Update policy**.

✅ **WATCH FOR:** the sidebar's Governance panel showing the updated policy JSON.

> **SAY:** "This is a governance-only rule — the node manager knows nothing about it. I'm about to
> block an agent type the node manager was never even asked about."

### 3.3 — Evolve the denied agent
**DO:** Twin row → set agent type to `rogue-agent` → **Evolve selected activity**.

🚫 **WATCH FOR (blocked):** red — `Agent type 'rogue-agent' is denied by governance policy`.

> **SAY:** "That message is the tell — it says *governance*, not 'not found in catalog.' The node
> manager was never contacted; governance stopped it first."

**DO:** Governance row → **View usage for this twin**.

✅ **WATCH FOR:** the count is unchanged. A denylist block doesn't burn quota.

### 3.4 — Use up the quota
**DO:** Agent type back to `validator` → **Evolve selected activity**.

✅ **WATCH FOR (approved):** **View usage for this twin** now reads 3 of 3.

### 3.5 — Hit the quota block
**DO:** **Evolve selected activity** once more.

🚫 **WATCH FOR (blocked):** red — `Evolution quota exceeded for twin process ... (3/3)`.

> **SAY:** "Same twin, same allowed agent type — but it's used its quota, so governance shuts it
> down regardless of what the node manager would say."

---

## 4. The one artifact that proves the whole chain

The sidebar's **Twin Event Log** has been filling in the whole time — every stage in order: the
definition deploying, both instances starting, the connect, the automatic bridge forward, then
every manual evolve attempt with exactly why it was approved or blocked.

**DO (optional):** open `localhost:8082/api/v1/wb/transmute/twin/<twinId>` in a new tab for the raw
JSON behind that panel.

> **SAY:** "If anyone asks 'how do I know governance actually ran before the node manager,' this is
> the receipt."

---

## Troubleshooting — if something looks wrong mid-demo

**Bridge says "already forwarded to twin"**
Not a bug — some evolution (manual or bridge) already succeeded for that twin+activity. Either
point at it as proof the idempotency guard works, or click **Deploy + Twin** for a fresh twin and
Bridge that before touching Evolve.

**Evolve says "Twin process instance ... could not be updated (it may have already ended)"**
Your process has no wait state — every instance finished the moment it started. Put a user task in
the diagram (see 1.1).

**Evolve says "not found in node manager catalog" instead of a governance message**
The denylist update didn't take — click **View policy** to confirm `rogue-agent` is actually
listed, then retry.

**Quota block shows up earlier or later than expected**
Every **Deploy + Twin** creates a brand-new twin with its own counter starting at zero, and Bridge
uses one slot before Task 3 even starts. If the count still looks off you're probably on an older
twin id — deploy a fresh one.

**Everything 404s / CORS errors in the console**
The frontend must be on port 3000; that's the only origin the backend's `WebSecurityConfig` allows.

**A fix you just made doesn't show up**
A stale Spring Boot process on 8082/8083 is serving old compiled code. See step 0.
