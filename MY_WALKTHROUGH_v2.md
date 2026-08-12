# My walkthrough, start to finish

Personal quick-reference. Every task gets an explicit Connect → Bridge/Evolve → Complete, not the quieter auto-bridge version, more to actually show on a recording.

---

## 1. Start fresh

**Terminal 1, node manager:**
```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\backend
.\mvnw.cmd -pl nodemanager spring-boot:run
```
Wait for `Tomcat started on port(s): 8083`.

**Terminal 2, workbench backend:**
```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\backend
.\mvnw.cmd -pl workbench install -DskipTests
.\mvnw.cmd -pl wbapi spring-boot:run
```
Wait for `Tomcat started on port(s): 8082`.

**Terminal 3, frontend:**
```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\frontend
npm start
```
Opens `localhost:3000` automatically. Go to `http://localhost:3000/wb/model`.

If any terminal says a port's already in use, something's still running from before, kill it first:
```
Get-NetTCPConnection -LocalPort 8082,8083,3000 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
```

---

## 2. Load the process
1. Click **New**
2. Click **Import** → select `citibank-wire-transfer.bpmn` from `C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\examples\`
3. Full diagram renders, Start, KYC, gateway, 3 parallel tasks, more gateways, Approve/Execute/Notify, End

## 3. Show the data
1. Click empty canvas → sidebar shows process-level Data (`transferAmount`, `customerId`, `destinationCountry`, `riskScore`)
2. Click the KYC task → shows its own separate task-level Data (`identityDocumentType`, `identityVerified`)

## 4. Raise the quota
1. Governance row → type `20` into **Max evolutions/twin**
2. Click **Update policy**

## 5. Deploy
1. Click **Deploy + Twin**
2. Green line shows original + twin instance ids and a twin id
3. **Copy the twin id somewhere**, needed if you restart later

## 6. KYC
1. Click the **KYC** box on the canvas
2. **Connect selected activity**
3. **Bridge selected activity** → green: `approved → agent validator-agent-01`
4. **Complete current task(s)**

## 7. The three parallel checks, bridge each one explicitly
1. Click **Run AML Screening** → Connect selected activity → Bridge selected activity → green approval
2. Click **Check Sanctions List (OFAC)** → Connect selected activity → Bridge selected activity → green approval
3. Click **Assess Credit Risk** → Connect selected activity → Bridge selected activity → green approval
4. Click **Complete current task(s)** once, all 3 complete together, process moves forward

## 8. Approve, Execute, Notify, ONE AT A TIME, not batched like step 7

Unlike step 7, these three are NOT open together, each one only becomes reachable after
you complete the one before it. Do not connect all three up front. Fully finish each one
before touching the next:

1. Click **Approve Transfer Amount** → Connect selected activity → Bridge selected activity
   (or Evolve) → **Complete current task(s)**
2. Only now click **Execute Wire Transfer** → Connect selected activity → Bridge selected
   activity (or Evolve) → **Complete current task(s)**
3. Only now click **Notify Customer of Completion** → Connect selected activity → Bridge
   selected activity (or Evolve) → **Complete current task(s)**

If you get "Activity not yet reached in the original process instance" on any of these, it
means you jumped ahead, go click **Complete current task(s)** first (it'll tell you what it
just finished, or say "nothing to complete" if you're already caught up), then retry.

## 9. Confirm it's done
Click **Complete current task(s)** one more time → muted message, "nothing to complete", process genuinely ended.

## 10. Governance demo, fresh twin
1. Click **New** → **Deploy + Twin** again (separate twin, so it's not competing with the first one for quota)
2. Select the default task → Connect selected activity
3. Governance row → type `rogue-agent` into Denied agent types → Update policy
4. Set agent type box to `rogue-agent` → Evolve selected activity → red block message ("denied by governance policy")
5. Set agent type back to `validator`, keep evolving until quota blocks too, if you want to show both denylist and quota

## 11. Restart mid-demo (optional, good moment to include)
1. Go to Terminal 2, `Ctrl+C`, restart it with the same command
2. Back in browser, paste your saved twin id into the Twin box, click Refresh on the event log
3. Full history should still be there

## 12. Camunda Cockpit, the receipts
1. Open a new tab: `http://localhost:8082/camunda`
2. Log in, username `demo`, password `demo`
3. Click **Processes** → click **"Citi Bank - Large Wire Transfer Review"**
4. In the instance list, find the one whose **Business Key** starts with `twin-` (not `original-`)
5. Click into it → click **Variables** tab
6. Real rows like `evolvedAgent_Task_KYC = validator-agent-01`, straight from the engine, nothing to do with this app's own claims
