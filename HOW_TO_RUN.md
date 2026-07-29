# MetaML Workbench, setup and walkthrough

## 0. Setup

```powershell
Get-NetTCPConnection -LocalPort 8082,8083,3000 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

1. `cd backend` then `.\mvnw.cmd -pl nodemanager spring-boot:run`
2. `cd backend`, `.\mvnw.cmd -pl workbench install -DskipTests`, then `.\mvnw.cmd -pl wbapi spring-boot:run`
3. `cd frontend` then `npm start`
4. Open `http://localhost:3000/wb/model`
5. Governance row: clear Denied agent types, set Max evolutions/twin to 20, Update policy

Don't skip step 5. The default cap is 5 evolutions per twin and both examples need more than
that: `citibank-wire-transfer.bpmn` bridges 7 activities on its happy path, and
`grad-admission-review.bpmn` also needs 7, because its committee review is a multi-instance
activity that runs three times and each visit takes its own slot. Leave the cap at 5 and the
walkthrough dies partway through with a governance rejection.

Both files in `examples/` are fixtures for this app, not portable BPMN samples. Every user task
in them carries `delegateExpression="${agentExecutionDelegate}"`, which is a Spring bean that
only exists here, so deploying them to a stock Camunda engine fails at the first task completion.

---

## 1. Task 1

1. New, Import, `examples/citibank-wire-transfer.bpmn`
2. Click empty canvas
3. Click `Verify Customer Identity (KYC)`
4. Deploy + Twin
5. Click `Verify Customer Identity (KYC)` → Connect selected activity → Bridge selected activity
6. Complete current task(s)
7. Click `Run AML Screening` → Connect selected activity
8. Click `Check Sanctions List (OFAC)` → Connect selected activity
9. Click `Assess Credit Risk` → Connect selected activity
10. Complete current task(s)
11. Click `Approve Transfer Amount` → Connect selected activity → Bridge selected activity → Complete current task(s)
12. Click `Execute Wire Transfer` → Connect selected activity → Bridge selected activity → Complete current task(s)
13. Click `Notify Customer of Completion` → Connect selected activity → Bridge selected activity → Complete current task(s)
14. Complete current task(s)

---

## 2. Task 2 / 3, governance

1. New, Deploy + Twin
2. Click `Review Application` → Connect selected activity
3. Agent type `validator` → Evolve selected activity
4. Governance row: `rogue-agent` into Denied agent types → Update policy
5. Agent type `rogue-agent` → Evolve selected activity
6. View usage for this twin
7. Governance row: Max evolutions/twin to `1` → Update policy
8. Agent type `validator` → Evolve selected activity
9. Governance row: Max evolutions/twin back to `20` → Update policy

---

## 3. Camunda Cockpit

1. `localhost:8082/camunda`, log in `demo` / `demo`
2. Processes → Citi Bank - Large Wire Transfer Review
3. Instance list → the one with Business Key starting `twin-`
4. Variables tab
