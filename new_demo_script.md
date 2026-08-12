# MetaML Demo, Quick Script

Short version, hits the main points only. Bracketed lines are click cues.

---

## Before you record

Start all three servers, click New once the app loads.

```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\backend
.\mvnw.cmd -pl nodemanager spring-boot:run
```
```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\backend
.\mvnw.cmd -pl workbench install -DskipTests
.\mvnw.cmd -pl wbapi spring-boot:run
```
```
cd C:\Users\Nirman\Desktop\ITP_a\metaml-workbench-source-of-truth\frontend
npm start
```

---

## The script

> Quick heads up, this is built on Henry's version, I added some stuff on top and fixed a few bugs. Let me know if anything looks off.
>
> This is a wire transfer process, real bank-style compliance checks, not a toy example.

**DO:** Import citibank-wire-transfer.bpmn, raise quota to 20, Deploy + Twin

> Deploy plus twin starts two instances, the original, and a twin that picks up agents as it goes.

**DO:** Click KYC → Connect → Bridge → Complete current task(s)

> This one I click by hand, since it's the very first step. Everything after this happens on its own.

**DO:** Connect AML, OFAC, and Credit Risk (no Bridge), then Complete current task(s) once

> Watch, I didn't click Bridge on any of these, and the log just fills itself in. That's the whole point, nobody has to trigger it manually anymore.

**DO:** Approve → Execute → Notify: Connect, Bridge, Complete, one at a time, same pattern

> Same thing three more times, and that's the process actually finished end to end.

**DO:** New twin, deny "rogue-agent", try Evolve with it, then quota it out too

> This is governance, it blocks specific agents outright, and it caps how many any twin can request.

**DO:** Open localhost:8082/camunda, log in demo/demo, find the twin instance, click Variables

> And this is a totally separate tool showing the same result, so it's not just this app's own word for it.
>
> That's it, let me know what you think.
