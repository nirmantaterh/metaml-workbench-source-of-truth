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
import java.util.concurrent.TimeUnit;

/**
 * Fires the bridge automatically when the original process reaches an activity, so nobody has to
 * click "Bridge selected activity". Just a trigger - it calls the same service method the REST
 * endpoint does and leans on that method's own guards. The manual button still works.
 *
 * <p>Uses the ExecutionEvents the Camunda Spring Boot starter already publishes
 * (camunda.bpm.eventing.execution, on by default) instead of a hand-written BpmnParseListener.
 * Same thing, already written, and it leaves the engine's history handler alone - which matters
 * because hasReachedActivityInOriginal reads those history rows.
 */
@Component
public class AutoBridgeTrigger {

    private static final Logger logger = LoggerFactory.getLogger(AutoBridgeTrigger.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    // activities emit "start"/"end", sequence flows emit "take" - we only want entry
    private static final String ACTIVITY_START_EVENT_NAME = "start";
    // way more than the real cost (~ms), so it only trips on something actually stuck
    private static final long BRIDGE_TIMEOUT_SECONDS = 10;

    private final WorkbenchService workbenchService;

    // single thread: activity starts are ordered anyway, and it caps us at one extra connection
    private final ExecutorService bridgeExecutor;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            thread.setDaemon(true);
            return thread;
        });
    }

    // AFTER_COMMIT, not a plain @EventListener. The listener that raises this event runs
    // mid-command, before the engine flushes, so the HISTORIC_ACTIVITY_INSTANCE row isn't there
    // yet and every activity gets rejected with "has not been reached yet". Cost me an afternoon.
    // fallbackExecution so the event isn't silently dropped if there's no tx synchronization.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityStarted(ExecutionEvent event) {
        if (!ACTIVITY_START_EVENT_NAME.equals(event.getEventName())) {
            return;
        }
        String businessKey = event.getProcessBusinessKey();
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // twin runs the same definition and emits the same activity ids, so skip it or we
            // bridge everything twice. Silent - this is most events on a normal run.
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());
        String activityId = event.getCurrentActivityId();

        // The other thread is load-bearing, not an optimisation. AFTER_COMMIT still has the
        // committed transaction's resources bound to this thread (checked: isActualTransactionActive()
        // is true here, Spring unbinds the connection later in cleanupAfterCompletion), so a
        // same-thread call would JOIN an already-committed transaction and its writes only survive
        // by luck when autoCommit is restored. It would also pin that connection across the node
        // manager HTTP call. The worker gets its own transaction and connection.
        Future<?> bridged = bridgeExecutor.submit(() -> runBridge(twinId, activityId));

        // wait rather than fire-and-forget so the UI's refetch after "Complete current task(s)"
        // is deterministic. Holds no locks, and worst case we log a timeout.
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
            // normal, not an error: unconnected activities hit this, and so do the first few
            // activities of a launch (they fire before launchProcess registers the TwinProcess)
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        bridgeExecutor.shutdownNow();
    }
}
