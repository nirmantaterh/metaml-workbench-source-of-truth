package com.metaml.workbench.bridge;

import jakarta.annotation.PreDestroy;

import org.camunda.bpm.spring.boot.starter.event.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metaml.workbench.service.WorkbenchService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Fires the bridge on its own when the original reaches an activity, so nobody has to click
 * "Bridge selected activity". It's only a trigger - calls the same service method the REST
 * endpoint calls and leans on that method's guards. Manual button still works.
 *
 * <p>Rides the ExecutionEvents the Camunda Spring Boot starter already publishes
 * (camunda.bpm.eventing.execution, on by default) rather than a hand-written BpmnParseListener.
 * Tried the listener first; it also meant touching the engine's history handler, and
 * hasReachedActivityInOriginal reads exactly those history rows, so no thanks.
 */
@Component
public class AutoBridgeTrigger {

    private static final Logger logger = LoggerFactory.getLogger(AutoBridgeTrigger.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    // activities emit start/end, sequence flows emit "take". we only want entry.
    private static final String ACTIVITY_START_EVENT_NAME = "start";
    // real cost is milliseconds, this only trips if something is genuinely stuck
    private static final long BRIDGE_TIMEOUT_SECONDS = 10;
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final WorkbenchService workbenchService;

    // one thread on purpose: activity starts are ordered anyway, and it caps us at one extra conn
    private final ExecutorService bridgeExecutor;

    // set in @PreDestroy. the engine can still be committing while we're being torn down, and
    // submitting to a dead executor throws.
    private volatile boolean shuttingDown = false;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            thread.setDaemon(true);
            return thread;
        });
    }

    // AFTER_COMMIT, not a plain @EventListener. Whatever raises this event runs mid-command,
    // before the engine flushes, so the HISTORIC_ACTIVITY_INSTANCE row isn't there yet and every
    // single activity comes back "has not been reached yet". Cost me an afternoon.
    // fallbackExecution so the event isn't just dropped if there's no tx synchronization around.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityStarted(ExecutionEvent event) {
        // Nothing below may escape. Spring lets an afterCommit exception propagate straight out
        // of the commit call, so a throw here surfaces as the engine command failing - the user
        // sees "complete task" blow up because of a bridge that's meant to be a side effect.
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
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // twin runs the same definition and emits the same ids, so skip it or everything
            // gets bridged twice. no logging, this is most events on a normal run.
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());
        String activityId = event.getCurrentActivityId();
        // a bare "original-" key, or a scope execution with no activity of its own. neither can
        // bridge anything, and going ahead just writes junk into somebody's event log.
        if (twinId.isBlank() || activityId == null || activityId.isBlank()) {
            return;
        }
        if (shuttingDown) {
            return;
        }

        // Don't inline this back onto the current thread. AFTER_COMMIT still has the committed
        // transaction's resources bound here (isActualTransactionActive() is true, I checked -
        // Spring only unbinds later in cleanupAfterCompletion), so a same-thread call joins an
        // already-committed transaction and its writes survive on luck. Also pins that connection
        // across the node manager HTTP call. Worker thread gets its own tx and its own connection.
        Future<?> bridged;
        try {
            bridged = bridgeExecutor.submit(() -> runBridge(twinId, activityId));
        } catch (RejectedExecutionException e) {
            // shutdown raced us between the flag check and here
            logger.debug("Auto-bridge executor is gone, skipping activity {} on twin {}", activityId, twinId);
            return;
        }

        // waiting instead of fire-and-forget so the UI refetch after "Complete current task(s)"
        // always sees the bridge. no locks held here, worst case is a logged timeout.
        try {
            bridged.get(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Auto-bridge of activity {} on twin {} did not finish in time: {}",
                    activityId, twinId, e.toString());
        }
    }

    private void runBridge(String twinId, String activityId) {
        try {
            workbenchService.bridgeActivityEvent(twinId, activityId);
        } catch (RuntimeException e) {
            // debug, not warn - this is normal. unconnected activities land here, and so does
            // whatever the process hits first: those events fire before launchProcess has even
            // stored the TwinProcess. That's why KYC gets bridged by hand in the demo.
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    // shutdownNow() on its own interrupted whatever bridge was mid-flight, which can leave the
    // activity marked as forwarded with no agent actually assigned. Give it a moment first.
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        bridgeExecutor.shutdown();
        try {
            if (!bridgeExecutor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                bridgeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bridgeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
