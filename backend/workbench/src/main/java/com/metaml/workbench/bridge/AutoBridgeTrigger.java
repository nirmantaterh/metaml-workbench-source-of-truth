package com.metaml.workbench.bridge;

import jakarta.annotation.PreDestroy;

import org.camunda.bpm.spring.boot.starter.event.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.service.WorkbenchService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Fires the bridge automatically when the original reaches an activity - same service call the
// manual "Bridge selected activity" button makes, so the manual button still works too. Since the
// twin got a definition of its own it also moves the twin's token through that activity, right
// after the bridge has put the evolved agent on it, by correlating the message that activity's
// receive task is waiting on.
//
// That second half lives here and nowhere else for a reason. The obvious-looking home for it is
// AgentExecutionDelegate, the complete listener on the original's task, and that was tried: the
// listener runs inside the human's task-completion transaction, so a twin-side failure marks the
// transaction rollback-only and the person who clicked Complete gets an error and no progress. A
// try/catch doesn't save you, which is the same lesson already written up in that class. Here the
// original has committed, we're off the engine's thread, and the worst a broken twin can do is log.
@Component
public class AutoBridgeTrigger {

    private static final Logger logger = LoggerFactory.getLogger(AutoBridgeTrigger.class);

    // activities emit start/end, sequence flows emit "take". we only want entry.
    private static final String ACTIVITY_START_EVENT_NAME = "start";
    // real cost is milliseconds, this only trips if something is genuinely stuck. Has to stay
    // above NodeManagerClient's 1s connect + 2s read or we'd give up on a call still in flight,
    // and can't go much above it either: a multi-instance activity fires this once per visit and
    // the whole lot happens while the browser waits on one click. Went from 4 to 6 when the worker
    // picked up moving the twin's own token on top of the bridge call.
    private static final long BRIDGE_TIMEOUT_SECONDS = 6;
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final WorkbenchService workbenchService;

    // one thread on purpose: activity starts are ordered anyway, and it caps us at one extra conn.
    // Mutable (Phase 9/10 red team finding): a ProjectAutomationService.execute() with no timeout
    // of its own can hang the one thread this executor has forever, and the old code only ever
    // gave up WAITING for that thread (bridged.get(timeout)) without ever freeing it - every later
    // activity-start event across EVERY twin in the app then queued behind the same stuck thread
    // and silently timed out too, with no Incident anywhere to say why. A reference that gets
    // swapped for a fresh executor on timeout means one bad automation call can wedge itself, but
    // not every other twin in the process along with it.
    private final AtomicReference<ExecutorService> bridgeExecutor = new AtomicReference<>();

    // engine can still be committing during shutdown; submitting to a dead executor throws
    private volatile boolean shuttingDown = false;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor.set(newBridgeExecutor());
    }

    private static ExecutorService newBridgeExecutor() {
        ThreadFactory daemonThread = runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(daemonThread);
    }

    // has to be AFTER_COMMIT, not a plain @EventListener - a plain one runs before the engine
    // flushes and every activity comes back "has not been reached yet". cost me an afternoon.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityStarted(ExecutionEvent event) {
        // can't let anything escape here or Camunda surfaces it as the task-complete call failing
        try {
            handleActivityStarted(event);
        } catch (RuntimeException e) {
            logger.warn("Auto-bridge listener swallowed an error so the engine command survives: {}",
                    e.toString());
        }
    }

    private void handleActivityStarted(ExecutionEvent event) {
        if (!ACTIVITY_START_EVENT_NAME.equals(event.getEventName())) {
            return;
        }
        String businessKey = event.getProcessBusinessKey();
        if (!BusinessKeys.isOriginalKey(businessKey)) {
            // twin emits the same activity ids, skip it or everything bridges twice
            return;
        }
        String twinId = BusinessKeys.twinIdFromOriginalKey(businessKey);
        String activityId = event.getCurrentActivityId();
        // bare "original-" key or a scope execution with no activity - nothing to bridge
        if (twinId.isBlank() || activityId == null || activityId.isBlank()) {
            return;
        }
        if (shuttingDown) {
            return;
        }
        // lets a loop/multi-instance activity's repeat visits bridge separately instead of
        // getting skipped as "already forwarded". Also what tells a parallel multi-instance
        // activity's several simultaneously-waiting twin siblings apart: bridgeActivityEvent
        // resolves this specific visit's own execution and, by its loopCounter, the one twin
        // sibling that corresponds to it.
        String activityInstanceId = event.getActivityInstanceId();

        // same-thread call would join the already-committed transaction on luck -
        // isActualTransactionActive() is still true here, Spring only unbinds it later
        ExecutorService executor = bridgeExecutor.get();
        Future<?> bridged;
        try {
            bridged = executor.submit(() -> runBridge(twinId, activityId, activityInstanceId));
        } catch (RejectedExecutionException e) {
            // shutdown raced us between the flag check and here
            logger.debug("Auto-bridge executor is gone, skipping activity {} on twin {}", activityId, twinId);
            return;
        }

        // wait so the UI refetch after "Complete current task(s)" always sees the bridge result
        try {
            bridged.get(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Auto-bridge of activity {} on twin {} did not finish in time: {}",
                    activityId, twinId, e.toString());
            // best-effort interrupt (helps if runBridge is blocked on something interruptible),
            // and - regardless of whether the interrupt actually lands - swap in a fresh executor
            // so every OTHER twin's auto-bridge doesn't queue behind this one's stuck thread
            // forever. compareAndSet so two events timing out on the same stuck task only replace
            // it once. The abandoned executor is left to finish or hang on its own daemon thread;
            // it can never accept new work again.
            bridged.cancel(true);
            if (bridgeExecutor.compareAndSet(executor, newBridgeExecutor())) {
                executor.shutdown();
                logger.warn("Auto-bridge worker replaced after activity {} on twin {} did not finish in time",
                        activityId, twinId);
            }
        }
    }

    // bridgeActivityEvent(twinId, activityId, activityInstanceId) does both halves itself now -
    // bridges/evolves, then (unless nothing was connected) advances the twin through this exact
    // visit. Used to be two separate calls here, the second reading loopCounter straight off this
    // event's own execution; consolidated so a manual caller with only an activityInstanceId (no
    // live ExecutionEvent to read) gets the identical parallel-multi-instance disambiguation this
    // trigger does, through one shared code path instead of two that could drift apart.
    private void runBridge(String twinId, String activityId, String activityInstanceId) {
        try {
            workbenchService.bridgeActivityEvent(twinId, activityId, activityInstanceId);
        } catch (RuntimeException e) {
            // debug not warn - unconnected activities land here normally, same as whatever the
            // process hits before launchProcess even registers the twin (why KYC is bridged by hand),
            // and a twin-advance failure inside bridgeActivityEvent is already caught and logged there
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    // shutdownNow() alone can interrupt a bridge mid-flight and leave it marked forwarded
    // with no agent actually assigned, so give it a moment first
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        ExecutorService executor = bridgeExecutor.get();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
