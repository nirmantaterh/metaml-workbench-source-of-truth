# MetaML Workbench, setup and walkthrough for Tasks 1-3

Everything happens on one page: `http://localhost:3000/wb/model`. Three toolbar rows (model,
twin, governance) and a sidebar on the right with element details, the twin event log and
governance results.

---

## 0. Setup

Kill anything stale on the ports first. A leftover Spring Boot process runs OLD compiled code
and will give a confident, wrong result:

```powershell
Get-NetTCPConnection -LocalPort 8082,8083,3000 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

Then three terminals:

1. Node manager on 8083 - `cd backend` then `.\mvnw.cmd -pl nodemanager spring-boot:run`
2. Workbench backend on 8082 - `cd backend`, then `.\mvnw.cmd -pl workbench install -DskipTests`,
   then `.\mvnw.cmd -pl wbapi spring-boot:run`
3. Frontend on 3000 - `cd frontend` then `npm start`

The frontend port matters - the backend's CORS config only allows `localhost:3000` and
`127.0.0.1:3000`.

Open `http://localhost:3000/wb/model`.

Governance row: clear Denied agent types, set Max evolutions/twin to 20, click Update policy.
The full walkthrough below touches 6 activities on one twin and the default of 3 will
quota-block partway through. (Section 3 does its own quota test later on a second twin so the
two don't compete for slots.)

---

## 1. Task 1, model, deploy, twin, connect, communicate, advance

### 1.1 Load the example

Top row: New, then Import, and pick `examples/citibank-wire-transfer.bpmn` from this repo.

Every step in this diagram is a user task on purpose: a start-event-only process deploys and
launches fine, but the instance runs to completion the instant it starts, leaving nothing to
evolve or bridge onto. A user task is a real wait state, which is what lets the process sit
there until it's explicitly advanced in 1.6.

### 1.2 Process and task data

Click the empty canvas background (selects the process root). The sidebar shows Data with
four process-level variables: `transferAmount`, `customerId`, `destinationCountry`, `riskScore`.

Click the `Verify Customer Identity (KYC)` task, it has its own Description and its own Data
rows (`identityDocumentType`, `identityVerified`), separate from the process-level data. Both
round-trip through the same BPMN XML as MetaML extension elements, no side database.

Note: with the process background selected, the Name box greys out. bpmn-js has no diagram
label for a process element, so that field would silently discard keystrokes. Use the
toolbar's Model name box to name the model itself.

### 1.3 Deploy + Twin

Top row, Deploy + Twin.

Expect a green line naming the original instance, the twin instance, and the twin id, and the
twin id lands in the Twin row. The Twin Event Log in the sidebar starts filling in. This starts
two real Camunda process instances from the same definition, the original and its twin.

### 1.4 KYC, the one activity bridged by hand

Click `Verify Customer Identity (KYC)`, then Connect selected activity, then Bridge selected
activity. Expect green: `approved, agent validator-agent-01`.

KYC is the one activity in this walkthrough that needs a manual Bridge click, it starts the
instant the process launches, before there's anything to connect yet. Every activity after
this one gets bridged automatically.

Then click Complete current task(s) on the Twin row. Expect:
`Completed 1 open task(s): Verify Customer Identity (KYC)... The next activity is now reachable.`
Completing the real Camunda task in the original instance is what moves the process to its
next step.

### 1.5 The parallel compliance checks

Connect all three, one at a time: `Run AML Screening`, `Check Sanctions List (OFAC)`,
`Assess Credit Risk`. Click each, then Connect selected activity.

Do not click Bridge or Evolve on any of them.

Click Complete current task(s). Expect two things. First:
`Completed 3 open task(s): Run AML Screening, Check Sanctions List (OFAC), Assess Credit Risk`,
all three at once, since it's a real parallel gateway with three tasks genuinely open together.
Then, with no further clicks, the event log fills in `Original activity ... reached`, `Forwarded
event to twin`, `Contacting node manager`, and an agent assigned, three times over, one per
activity. Nobody clicks Bridge for these three, the platform notices on its own once Camunda
genuinely advances the process, and does the full governance-checked, node-manager-checked
handoff automatically.

### 1.6 Approve, Execute, Notify

Same three steps for each of `Approve Transfer Amount`, `Execute Wire Transfer` and
`Notify Customer of Completion`, in that order: click the activity, Connect selected activity,
Complete current task(s). Don't touch Bridge or Evolve.

After each Complete, the event log auto-fills the same reached, forwarded, node manager, agent
assigned sequence, with no manual trigger.

Manual Bridge and Evolve buttons still work on any of these activities too, this is additive,
not a replacement. Clicking Bridge on an already-auto-bridged activity is a harmless idempotent
no-op (`already forwarded to the twin`, shown muted rather than red).

### 1.7 Confirm it's actually finished

Click Complete current task(s) one more time. Expect a muted, not red,
`No open user tasks on the original process instance, nothing to complete.` The instance has
genuinely run to its end event.

Same idea with a repeat Bridge on an already-forwarded activity: grey, not red,
`already forwarded to the twin`. Deliberate no-op, not a failure.

---

## 2. Task 2, node manager

No dedicated screen for this, it's the layer Task 3 sits in front of.

A separate Spring Boot server on port 8083 with its own catalog of agent types. Every Evolve
and Bridge above makes a real HTTP call out to that server asking whether an agent type is
available. Visible in the event log as `Contacting node manager for agent type ...`.

---

## 3. Task 3, governance

Use a fresh second twin here so it isn't competing with section 1's twin for evolution slots.
The default blank-canvas diagram is enough, the citibank process isn't needed.

New, then Deploy + Twin, then select `Review Application` and Connect selected activity.

**Evolve an allowed agent.** Twin row, agent type `validator`, Evolve selected activity.
Green, `agent validator-agent-01`. Validator is a real entry in the node manager's catalog and
governance has nothing against it.

**Set a denylist.** Governance row, type `rogue-agent` into Denied agent types, leave the quota
alone, Update policy.

**Evolve the denied agent.** Agent type `rogue-agent`, Evolve selected activity. Red:
`Agent type 'rogue-agent' is denied by governance policy`. This message names governance, not
"not found in catalog", the node manager was never contacted, governance stopped it first.

Click View usage for this twin: the count is unchanged from the first evolve. A denylist block
doesn't burn quota.

**Hit a quota block.** Governance row, set Max evolutions/twin to 1, Update policy (this twin
already used its one slot above), set agent type back to `validator`, Evolve selected activity.
Red: `Evolution quota exceeded for twin process ... (1/1)`. Same twin, same allowed agent type,
but it's used its quota.

Cleanup: set Max evolutions/twin back to 20 and Update policy, so the next run isn't blocked by
a leftover 1.

---

## 4. Camunda Cockpit, verifying it independently

The Twin Event Log fills in the whole time, every stage in order, the definition deploying,
both instances starting, every connect, every bridge/evolve attempt with exactly why it was
approved or blocked, and every task completion advancing the real process.

Optional: open `localhost:8082/api/v1/wb/transmute/twin/<twinId>` in a new tab for the raw JSON
behind that panel.

The higher-credibility check is a completely independent tool, the real Camunda Cockpit rather
than this app:

1. Open `localhost:8082/camunda` in a new tab, log in with `demo` / `demo`.
2. Processes, then Citi Bank - Large Wire Transfer Review.
3. In the running-instances list, find the one whose Business Key starts with `twin-`. The
   other one, `original-`, is the main process, don't open that one.
4. Click into it and open the Variables tab.

There's one `evolvedAgent_<activityId>` row per activity bridged or evolved, with real values,
e.g. `evolvedAgent_Task_KYC = validator-agent-01`, sitting on the instance in a tool that has
never heard of this workbench. This is Camunda's own console reading the engine's own database,
independent confirmation that governance ran before the node manager, and that the twin
actually has these agents attached.

---

## Troubleshooting

**Bridge says "already forwarded to twin"**, not a bug, some evolution (manual or bridge)
already succeeded for that twin and activity. Complete the current task and move to the next
activity.

**"No open user tasks... nothing to complete"**, either the instance already ran to its end
event (section 1.7, the success case), or Deploy + Twin hasn't been clicked yet for this canvas.

**"could not be updated (it may have already ended)"**, the activity's task got completed by an
earlier Complete current task(s) click before Evolve/Bridge was clicked on it. Or the diagram
has no wait state at all, see the note in 1.1.

**Evolve says "not found in node manager catalog" instead of a governance message**, the
denylist update didn't take. Click View policy to confirm the type is actually listed, then
retry.

**Quota block shows up earlier or later than expected**, every Deploy + Twin creates a
brand-new twin with its own counter starting at zero, and each Bridge/Evolve uses a slot. If
the count looks off, check the twin id in the Twin row, it's probably a different twin's usage.

**Everything 404s, or CORS errors in the console**, the frontend has to be on `localhost:3000`
or `127.0.0.1:3000`, those are the only two origins `WebSecurityConfig` allows.

**A fix doesn't show up**, stale Spring Boot process on 8082/8083 serving old compiled code.
See section 0.
