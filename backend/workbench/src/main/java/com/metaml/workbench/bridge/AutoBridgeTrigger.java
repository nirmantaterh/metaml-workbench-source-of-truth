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

// fires the bridge automatically when the original reaches an activity - same service call
// the manual "Bridge selected activity" button makes, so the manual button still works too
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

    // engine can still be committing during shutdown; submitting to a dead executor throws
    private volatile boolean shuttingDown = false;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            thread.setDaemon(true);
            return thread;
        });
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
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // twin emits the same activity ids, skip it or everything bridges twice
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());
        String activityId = event.getCurrentActivityId();
        // bare "original-" key or a scope execution with no activity - nothing to bridge
        if (twinId.isBlank() || activityId == null || activityId.isBlank()) {
            return;
        }
        if (shuttingDown) {
            return;
        }
        // lets a loop/multi-instance activity's repeat visits bridge separately instead of
        // getting skipped as "already forwarded"
        String activityInstanceId = event.getActivityInstanceId();

        // same-thread call would join the already-committed transaction on luck -
        // isActualTransactionActive() is still true here, Spring only unbinds it later
        Future<?> bridged;
        try {
            bridged = bridgeExecutor.submit(() -> runBridge(twinId, activityId, activityInstanceId));
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
        }
    }

    private void runBridge(String twinId, String activityId, String activityInstanceId) {
        try {
            workbenchService.bridgeActivityEvent(twinId, activityId, activityInstanceId);
        } catch (RuntimeException e) {
            // debug not warn - unconnected activities land here normally, same as whatever the
            // process hits before launchProcess even registers the twin (why KYC is bridged by hand)
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    // shutdownNow() alone can interrupt a bridge mid-flight and leave it marked forwarded
    // with no agent actually assigned, so give it a moment first
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
