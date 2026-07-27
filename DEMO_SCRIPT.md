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
      (port matters: the backend's CORS config only allows `localhost:3000` and `127.0.0.1:3000`)
- [ ] Browser at `http://localhost:3000/wb/model`, window wide enough that all three toolbar rows
      and the sidebar are visible without scrolling
- [ ] Governance row → clear **Denied agent types**, set **Max evolutions/twin** to `20`, click
      **Update policy**. This walkthrough touches 6 activities across one twin — the old default of
      `3` will quota-block partway through and look exactly like a bug. (Task 3's own quota-block
      demo, further down, deliberately uses a *second*, freshly-deployed twin so it isn't fighting
      this one for slots.)

> **SAY:** "Today I'm walking through Tasks 1 through 3 of the MetaML workbench on a realistic
> multi-step process — a Citi Bank wire-transfer review — modeling it, deploying it with a twin,
> and letting the two communicate **automatically** as the process actually advances step by step —
> no button-click required for that communication past the very first activity — while requesting
> agents through the node manager and enforcing governance on top of that. Everything you'll see is
> live — real Camunda engine, real HTTP calls, nothing mocked."

---

## 1. Task 1 — model, deploy, twin, connect, communicate, advance

### 1.1 — Load the example process
**DO:** Top row → **New**, then **Import** → pick `examples/citibank-wire-transfer.bpmn` from this
repo.

> **SAY:** "This is a real bpmn-js editor — the same library Camunda's own Modeler uses. Rather
> than a toy diagram, this is a realistic compliance process: KYC, three compliance checks running
> in parallel, a manager approval with a timeout, execution, and customer notification."

⚠️ **Why a user task matters at every step:** a start-event-only process deploys and launches fine,
but the process instance runs to completion the instant it starts, so there's nothing left to
evolve or bridge onto. Every step in this diagram is a genuine Camunda user task — a real wait
state — which is what lets the process sit there until you explicitly advance it (§1.6).

### 1.2 — Show process *and* task data
**DO:** Click empty canvas background (selects the process root) → sidebar shows **Data**: four
process-level variables (`transferAmount`, `customerId`, `destinationCountry`, `riskScore`).

**DO:** Click the `Verify Customer Identity (KYC)` task → sidebar shows a **Description** and its
own **Data** rows (`identityDocumentType`, `identityVerified`) — separate from the process-level
data.

> **SAY:** "Data can live at the process level or on an individual task — both round-trip through
> the same BPMN XML as MetaML extension elements, no side database to keep in sync."

ℹ️ Select the process background and the **Name** box greys out with a note — bpmn-js has no
diagram label for a process element, so the field would silently throw keystrokes away. Use the
toolbar's **Model name** box to name the model itself.

### 1.3 — Deploy + Twin
**DO:** Top row → **Deploy + Twin**.

✅ **WATCH FOR:** a green line naming the **original instance**, the **twin instance**, and the
**twin id** — and the twin id landing in the Twin row. The sidebar's **Twin Event Log** starts
filling in.

> **SAY:** "That starts two real Camunda process instances from the same definition — the original
> and its twin."

### 1.4 — KYC: the one activity that has to be bridged manually, and why
**DO:** Click `Verify Customer Identity (KYC)` → **Connect selected activity** → **Bridge selected
activity**.

✅ **WATCH FOR (approved):** green, `approved → agent validator-agent-01`.

> **SAY:** "Bridge is the piece that satisfies 'the twin process activities communicate.' It reads
> Camunda's own execution history for the original, sees that KYC was genuinely reached, and
> forwards that event to the twin through the same governance and node-manager path a manual
> request would use. KYC is the one activity in this whole walkthrough where I have to click that
> button myself — it starts the instant the process launches, before there's anything to connect
> yet. Every activity after this one, the platform bridges on its own — watch."

**DO:** Twin row → **Complete current task(s)**.

✅ **WATCH FOR:** `Completed 1 open task(s): Verify Customer Identity (KYC)... The next activity is
now reachable.`

> **SAY:** "Completing the real Camunda task in the *original* instance is what moves the process to
> its next step instead of sitting frozen at activity one forever."

### 1.5 — The parallel compliance checks: connect, complete, never touch Bridge
**DO:** Click `Run AML Screening` → **Connect selected activity**. Click `Check Sanctions List
(OFAC)` → **Connect selected activity**. Click `Assess Credit Risk` → **Connect selected activity**.

**Do not click Bridge or Evolve on any of them.**

**DO:** Twin row → **Complete current task(s)**.

✅ **WATCH FOR:** `Completed 3 open task(s): Run AML Screening, Check Sanctions List (OFAC), Assess
Credit Risk` — all three at once (a real Camunda parallel gateway, three tasks genuinely open
together) — **and then, with zero further clicks**, the Twin Event Log fills in `Original activity
... reached` → `Forwarded event to twin` → `Contacting node manager` → an agent assigned, three
times over, one per activity.

> **SAY:** "Nobody clicked Bridge for any of these three. The moment Camunda genuinely advances the
> original process, the platform notices on its own and does the whole governance-checked,
> node-manager-checked agent handoff — automatically, in the background. That's the actual answer
> to 'does this require a human to click a button to communicate' — it doesn't, past the very first
> step."

### 1.6 — Approve, Execute, Notify
**DO, repeated three times** (once per activity): click the activity → **Connect selected
activity** → **Complete current task(s)**. Don't click Bridge or Evolve on these either.

Order: `Approve Transfer Amount` → `Execute Wire Transfer` → `Notify Customer of Completion`.

✅ **WATCH FOR**, after each Complete: the event log auto-fills the same
reached→forwarded→node-manager→agent-assigned sequence with no manual trigger.

> **SAY:** "Same automatic mechanism, three more times, on activities that only became reachable
> because we kept advancing the real process. This is the difference between 'we can poke at the
> first box of a diagram' and 'here's a process actually running through to the end, talking to its
> twin the whole way, without anyone driving it by hand.'"

ℹ️ **If you want to show manual Evolve too** (a human explicitly picking an agent, as distinct from
the automatic bridge): the manual **Bridge**/**Evolve** buttons still work exactly as before — this
is additive, not a replacement. Clicking Bridge on an already-auto-bridged activity is a harmless,
idempotent no-op (`already forwarded to the twin`, shown muted, not red) — good to demonstrate on
purpose if asked "what happens if I click it anyway."

### 1.7 — Confirm it's actually done
**DO:** Click **Complete current task(s)** one more time.

✅ **WATCH FOR:** a muted (not red) `No open user tasks on the original process instance — nothing
to complete.` The process instance has genuinely run to its end event.

> **SAY:** "That's not an error — that's the honest answer once there's nothing left to advance."

⚠️ **A repeat Bridge on an already-forwarded activity** (e.g. bridging KYC twice) shows a **grey,
not red**, `already forwarded to the twin` line — a deliberate no-op, not a failure. The backend
reports it in the same shape as a real denial, so the page special-cases it rather than crying wolf.

---

## 2. Task 2 — node manager

No dedicated screen — it's the layer Task 3 sits in front of.

> **SAY:** "Task 2 is the node manager — a separate Spring Boot server on port 8083 with its own
> catalog of agent types. Every Evolve and Bridge you just saw made a real HTTP call out to that
> server asking 'is this agent type available?' You can see that call in the event log."

---

## 3. Task 3 — governance, layered in front

Use a **fresh, second twin** for this section so it isn't competing with §1's twin for evolution
slots — the default diagram (blank canvas) is enough; you don't need the citibank process for this.

**DO:** **New** → **Deploy + Twin** → select `Review Application` → **Connect selected activity**.

### 3.1 — Evolve an allowed agent
**DO:** Twin row → agent type `validator` → **Evolve selected activity**.

✅ **WATCH FOR (approved):** green, `agent validator-agent-01`.

> **SAY:** "Validator is a real entry in the node manager's catalog and governance has nothing
> against it, so it goes through."

### 3.2 — Set a governance denylist
**DO:** Governance row → type `rogue-agent` into **Denied agent types**, leave quota alone → click
**Update policy**.

> **SAY:** "This is a governance-only rule — the node manager knows nothing about it. I'm about to
> block an agent type the node manager was never even asked about."

### 3.3 — Evolve the denied agent
**DO:** Agent type → `rogue-agent` → **Evolve selected activity**.

🚫 **WATCH FOR (blocked):** red — `Agent type 'rogue-agent' is denied by governance policy`.

> **SAY:** "That message says *governance*, not 'not found in catalog' — the node manager was never
> contacted; governance stopped it first."

**DO:** **View usage for this twin**.

✅ **WATCH FOR:** count unchanged from §3.1. A denylist block doesn't burn quota.

### 3.4 — Hit a quota block
**DO:** Governance row → set **Max evolutions/twin** to `1` → **Update policy** (this twin already
used its one slot in §3.1) → agent type back to `validator` → **Evolve selected activity**.

🚫 **WATCH FOR (blocked):** red — `Evolution quota exceeded for twin process ... (1/1)`.

> **SAY:** "Same twin, same allowed agent type — but it's used its quota, so governance shuts it
> down regardless of what the node manager would say."

**DO (cleanup):** Governance row → set **Max evolutions/twin** back to `20` → **Update policy**, so
the next person to run this script isn't blocked by a `1` left over from this demo.

---

## 4. The one artifact that proves the whole chain

The sidebar's **Twin Event Log** has been filling in the whole time — every stage in order: the
definition deploying, both instances starting, every connect, every bridge/evolve attempt with
exactly why it was approved or blocked, and every task completion advancing the real process.

**DO (optional):** open `localhost:8082/api/v1/wb/transmute/twin/<twinId>` in a new tab for the raw
JSON behind that panel. **Also optional, high-credibility:** Camunda Cockpit is running at
`localhost:8082` (login `demo`/`demo`) — open the twin's process instance there and show its live
`evolvedAgent_*` variables read directly from the engine, not from anything the workbench itself
wrote about itself.

> **SAY:** "If anyone asks 'how do I know governance actually ran before the node manager, and that
> the twin really has these agents attached,' this is the receipt."

---

## Troubleshooting — if something looks wrong mid-demo

**Bridge says "already forwarded to twin"**
Not a bug — some evolution (manual or bridge) already succeeded for that twin+activity. Point at it
as proof the idempotency guard works, or Complete the current task and move to the next activity.

**Complete current task(s) says "No open user tasks... nothing to complete"**
Either the process instance already ran to its end event (§1.7 — this is the success case), or you
haven't clicked Deploy + Twin yet for this canvas.

**Evolve/Bridge says "could not be updated (it may have already ended)"**
The activity's task already got completed by a previous **Complete current task(s)** click before
you got to Evolve/Bridge it, or your diagram has no wait state at all (see §1.1's warning).

**Evolve says "not found in node manager catalog" instead of a governance message**
The denylist update didn't take — click **View policy** to confirm the type is actually listed,
then retry.

**Quota block shows up earlier or later than expected**
Every **Deploy + Twin** creates a brand-new twin with its own counter starting at zero, and each
Bridge/Evolve uses one slot. If the count looks off you're probably reading a different twin's usage
— double check the twin id in the Twin row matches what you expect.

**Everything 404s / CORS errors in the console**
The frontend must be on `localhost:3000` or `127.0.0.1:3000` — those are the only two origins the
backend's `WebSecurityConfig` allows.

**A fix you just made doesn't show up**
A stale Spring Boot process on 8082/8083 is serving old compiled code. See step 0.
