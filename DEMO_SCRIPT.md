# MetaML Workbench - demo run for Tasks 1-3

Notes for presenting this to the team. What to click, roughly what to say, and what should
happen - including a few things that already went wrong on a real run.

Everything happens on one page: `http://localhost:3000/wb/model`. Three toolbar rows (model,
twin, governance) and a sidebar on the right with element details, the twin event log and
governance results.

---

## 0. Setup, before anyone's watching

Kill anything stale on the ports first. A leftover Spring Boot process runs OLD compiled code
and will give you a confident, wrong demo:

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

Open `http://localhost:3000/wb/model` and make the window wide enough that all three toolbar
rows and the sidebar fit without scrolling.

Last thing: on the Governance row, clear Denied agent types, set Max evolutions/twin to 20, and
click Update policy. This walkthrough touches 6 activities on one twin and the default of 3 will
quota-block partway through, which looks exactly like a bug. (Task 3 does its own quota demo
later on a second twin so the two don't fight over slots.)

Opening line, roughly:

> "Today I'm walking through Tasks 1 through 3 of the MetaML workbench on a realistic multi-step
> process - a Citi Bank wire-transfer review. Modeling it, deploying it with a twin, and letting
> the two communicate automatically as the process advances, with no button-click needed for that
> communication past the very first activity - while requesting agents through the node manager
> and enforcing governance on top. Everything is live: real Camunda engine, real HTTP calls,
> nothing mocked."

---

## 1. Task 1 - model, deploy, twin, connect, communicate, advance

### 1.1 Load the example

Top row: New, then Import, and pick `examples/citibank-wire-transfer.bpmn` from this repo.

> "This is a real bpmn-js editor, the same library Camunda's own Modeler uses. And rather than a
> toy diagram this is a realistic compliance process - KYC, three compliance checks running in
> parallel, a manager approval with a timeout, execution, and customer notification."

Worth knowing why every step here is a user task: a start-event-only process deploys and launches
fine, but the instance runs to completion the instant it starts, so there's nothing left to evolve
or bridge onto. Every step in this diagram is a genuine Camunda user task - a real wait state -
which is what lets the process sit there until you explicitly advance it in 1.6.

### 1.2 Show process and task data

Click the empty canvas background (that selects the process root). The sidebar shows Data with
four process-level variables: `transferAmount`, `customerId`, `destinationCountry`, `riskScore`.

Now click the `Verify Customer Identity (KYC)` task - it has its own Description and its own Data
rows (`identityDocumentType`, `identityVerified`), separate from the process-level data.

> "Data can live at the process level or on an individual task. Both round-trip through the same
> BPMN XML as MetaML extension elements - no side database to keep in sync."

Small thing you'll notice: with the process background selected the Name box greys out with a
note. bpmn-js has no diagram label for a process element, so that field would silently throw
keystrokes away. Use the toolbar's Model name box to name the model itself.

### 1.3 Deploy + Twin

Top row, Deploy + Twin.

You should get a green line naming the original instance, the twin instance and the twin id, and
the twin id lands in the Twin row. The Twin Event Log in the sidebar starts filling in.

> "That starts two real Camunda process instances from the same definition - the original and its
> twin."

### 1.4 KYC - the one activity you bridge by hand

Click `Verify Customer Identity (KYC)`, then Connect selected activity, then Bridge selected
activity. Expect green: `approved -> agent validator-agent-01`.

> "Bridge is the piece that satisfies 'the twin process activities communicate.' It reads
> Camunda's own execution history for the original, sees that KYC was genuinely reached, and
> forwards that event to the twin through the same governance and node-manager path a manual
> request would use. KYC is the one activity in this whole walkthrough where I have to click that
> button myself - it starts the instant the process launches, before there's anything to connect
> yet. Every activity after this one, the platform bridges on its own. Watch."

Then hit Complete current task(s) on the Twin row. You should see
`Completed 1 open task(s): Verify Customer Identity (KYC)... The next activity is now reachable.`

> "Completing the real Camunda task in the original instance is what moves the process to its
> next step instead of sitting frozen at activity one forever."

### 1.5 The parallel compliance checks

Connect all three, one at a time: `Run AML Screening`, `Check Sanctions List (OFAC)`,
`Assess Credit Risk`. Click each, then Connect selected activity.

Do not click Bridge or Evolve on any of them. That's the whole point of this bit.

Now Complete current task(s). Two things should happen. First:
`Completed 3 open task(s): Run AML Screening, Check Sanctions List (OFAC), Assess Credit Risk` -
all three at once, because it's a real parallel gateway with three tasks genuinely open together.
Then, with no further clicks, the event log fills in `Original activity ... reached`, `Forwarded
event to twin`, `Contacting node manager`, and an agent assigned - three times over, one per
activity.

> "Nobody clicked Bridge for any of these three. The moment Camunda genuinely advances the
> original process, the platform notices on its own and does the whole governance-checked,
> node-manager-checked agent handoff, automatically, in the background. That's the actual answer
> to 'does this need a human to click a button to communicate' - it doesn't, past the first step."

### 1.6 Approve, Execute, Notify

Same three steps for each of `Approve Transfer Amount`, `Execute Wire Transfer` and
`Notify Customer of Completion`, in that order: click the activity, Connect selected activity,
Complete current task(s). Again, don't touch Bridge or Evolve.

After each Complete the event log auto-fills the same reached -> forwarded -> node manager ->
agent assigned sequence, with no manual trigger.

> "Same automatic mechanism, three more times, on activities that only became reachable because
> we kept advancing the real process. This is the difference between 'we can poke at the first box
> of a diagram' and 'here's a process actually running through to the end, talking to its twin the
> whole way, without anyone driving it by hand.'"

If you want to show manual Evolve as well (a human explicitly picking an agent, as distinct from
the automatic bridge), the manual Bridge and Evolve buttons still work exactly as before - this is
additive, not a replacement. Clicking Bridge on an already-auto-bridged activity is a harmless
idempotent no-op (`already forwarded to the twin`, shown muted rather than red), which is a good
one to demo on purpose if someone asks what happens if they click it anyway.

### 1.7 Confirm it's actually finished

Click Complete current task(s) one more time. You should get a muted, not red,
`No open user tasks on the original process instance - nothing to complete.` The instance has
genuinely run to its end event.

> "That's not an error - that's the honest answer once there's nothing left to advance."

Same idea with a repeat Bridge on an already-forwarded activity (bridging KYC twice, say): grey,
not red, `already forwarded to the twin`. Deliberate no-op, not a failure. The backend reports it
in the same shape as a real denial, so the page special-cases it rather than crying wolf.

---

## 2. Task 2 - node manager

No dedicated screen for this one - it's the layer Task 3 sits in front of.

> "Task 2 is the node manager, a separate Spring Boot server on port 8083 with its own catalog of
> agent types. Every Evolve and Bridge you just saw made a real HTTP call out to that server
> asking 'is this agent type available?' You can see that call in the event log."

---

## 3. Task 3 - governance

Use a fresh second twin here so it isn't competing with section 1's twin for evolution slots. The
default blank-canvas diagram is enough, you don't need the citibank process.

New, then Deploy + Twin, then select `Review Application` and Connect selected activity.

**Evolve an allowed agent.** Twin row, agent type `validator`, Evolve selected activity. Green,
`agent validator-agent-01`.

> "Validator is a real entry in the node manager's catalog and governance has nothing against it,
> so it goes through."

**Set a denylist.** Governance row, type `rogue-agent` into Denied agent types, leave the quota
alone, Update policy.

> "This is a governance-only rule - the node manager knows nothing about it. I'm about to block an
> agent type the node manager was never even asked about."

**Evolve the denied agent.** Agent type `rogue-agent`, Evolve selected activity. Red:
`Agent type 'rogue-agent' is denied by governance policy`.

> "That message says governance, not 'not found in catalog'. The node manager was never contacted
> - governance stopped it first."

Click View usage for this twin: the count is unchanged from the first evolve. A denylist block
doesn't burn quota, which is worth pointing out.

**Hit a quota block.** Governance row, set Max evolutions/twin to 1, Update policy (this twin
already used its one slot above), set agent type back to `validator`, Evolve selected activity.
Red: `Evolution quota exceeded for twin process ... (1/1)`.

> "Same twin, same allowed agent type, but it's used its quota, so governance shuts it down
> regardless of what the node manager would say."

Cleanup: set Max evolutions/twin back to 20 and Update policy, so whoever runs this script next
isn't blocked by a leftover 1.

---

## 4. The artifact that proves the whole chain

The Twin Event Log has been filling in the whole time, every stage in order - the definition
deploying, both instances starting, every connect, every bridge/evolve attempt with exactly why it
was approved or blocked, and every task completion advancing the real process.

Optional: open `localhost:8082/api/v1/wb/transmute/twin/<twinId>` in a new tab for the raw JSON
behind that panel.

Also optional, and probably the highest-credibility moment available - show the same fact from a
completely independent tool, the real Camunda Cockpit rather than this app:

1. Open `localhost:8082/camunda` in a new tab, log in with `demo` / `demo`.
2. Processes, then Citi Bank - Large Wire Transfer Review.
3. In the running-instances list, find the one whose Business Key starts with `twin-`. The other
   one, `original-`, is the main process - don't open that one.
4. Click into it and open the Variables tab.

There's one `evolvedAgent_<activityId>` row per activity you bridged or evolved, with real values,
e.g. `evolvedAgent_Task_KYC = validator-agent-01` - sitting on the instance in a tool that has
never heard of this workbench.

> "If anyone asks how you know governance actually ran before the node manager, and that the twin
> really has these agents attached - this is the receipt. And it's not even this app telling you.
> This is Camunda's own console reading the engine's own database."

---

## Troubleshooting

**Bridge says "already forwarded to twin"** - not a bug, some evolution (manual or bridge) already
succeeded for that twin and activity. Point at it as proof the idempotency guard works, or
complete the current task and move to the next activity.

**"No open user tasks... nothing to complete"** - either the instance already ran to its end event
(section 1.7, which is the success case), or you haven't clicked Deploy + Twin yet for this canvas.

**"could not be updated (it may have already ended)"** - the activity's task got completed by an
earlier Complete current task(s) click before you got to Evolve/Bridge it. Or your diagram has no
wait state at all, see the note in 1.1.

**Evolve says "not found in node manager catalog" instead of a governance message** - the denylist
update didn't take. Click View policy to confirm the type is actually listed, then retry.

**Quota block shows up earlier or later than expected** - every Deploy + Twin creates a brand-new
twin with its own counter starting at zero, and each Bridge/Evolve uses a slot. If the count looks
off you're probably reading a different twin's usage, so double-check the twin id in the Twin row.

**Everything 404s, or CORS errors in the console** - the frontend has to be on `localhost:3000` or
`127.0.0.1:3000`, those are the only two origins `WebSecurityConfig` allows.

**A fix you just made doesn't show up** - stale Spring Boot process on 8082/8083 serving old
compiled code. See step 0.
