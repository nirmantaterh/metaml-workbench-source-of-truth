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
 * Makes the bridge fire on its own when the ORIGINAL process instance actually reaches an
 * activity, instead of waiting for a human to click "Bridge selected activity".
 *
 * <p>The trigger only: it calls the exact same {@link WorkbenchService#bridgeActivityEvent}
 * the REST endpoint calls, and relies on that method's existing guards (linked / actually
 * reached / already-forwarded) rather than repeating any of them here. The manual endpoint is
 * untouched and still works -- a manual bridge after the automatic one already fired is the
 * no-op the idempotency marker was built for.
 *
 * <p><b>Why an ExecutionEvent and not a custom engine hook.</b> The Camunda Spring Boot starter
 * (7.22) already ships {@code PublishDelegateParseListener}, enabled by default via
 * {@code camunda.bpm.eventing.execution=true}. At parse time it attaches start/end
 * {@code ExecutionListener}s to every activity of every deployed definition and republishes them
 * as Spring {@link ExecutionEvent}s. That is exactly the "BpmnParseListener registering an
 * ExecutionListener" approach, already written, already covering gateways/events/tasks uniformly,
 * and -- unlike wrapping the engine's HistoryEventHandler -- it leaves history recording strictly
 * alone. That matters: hasReachedActivityInOriginal and completeCurrentTasks both depend on
 * Camunda's normal history/task rows continuing to be written exactly as before.
 *
 * <p><b>Why the twin instance must be ignored.</b> Original and twin are two instances of the SAME
 * process definition, so they emit events for the same activity ids. launchProcess gives the
 * original the business key {@code original-<twinId>} and the twin {@code twin-<twinId>}; only the
 * former is a real "the original got here" signal. Anything else would double-bridge.
 */
@Component
public class AutoBridgeTrigger {

    private static final Logger logger = LoggerFactory.getLogger(AutoBridgeTrigger.class);

    private static final String ORIGINAL_BUSINESS_KEY_PREFIX = "original-";
    // ExecutionEvent carries the raw Camunda event name; activities emit "start" and "end", and
    // sequence flows emit "take". Only the moment an activity is entered is a bridge trigger.
    private static final String ACTIVITY_START_EVENT_NAME = "start";
    // Generous relative to the real cost (a couple of local queries plus one node manager call,
    // single-digit milliseconds), so it only ever trips on something genuinely wedged.
    private static final long BRIDGE_TIMEOUT_SECONDS = 10;

    private final WorkbenchService workbenchService;

    // Single-threaded on purpose: activity starts for one instance are inherently ordered, and one
    // worker means this can never need more than one extra pooled connection.
    private final ExecutorService bridgeExecutor;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            // Daemon so a wedged bridge can never keep the JVM alive after shutdown.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * AFTER_COMMIT, not a plain {@code @EventListener}: the ExecutionListener that produces this
     * event runs mid-command, before the engine has flushed anything. Bridging from there is not
     * merely risky in theory, it silently does nothing -- bridgeActivityEvent's
     * hasReachedActivityInOriginal check reads HISTORIC_ACTIVITY_INSTANCE, and the row for the
     * activity that just started is still an unflushed insert in the command context, so every
     * single activity is rejected with "has not been reached in the original process instance yet".
     *
     * <p>fallbackExecution covers the (not observed here) case of an engine command running with no
     * transaction synchronization at all; without it Spring would drop the event silently.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityStarted(ExecutionEvent event) {
        if (!ACTIVITY_START_EVENT_NAME.equals(event.getEventName())) {
            return;
        }
        String businessKey = event.getProcessBusinessKey();
        if (businessKey == null || !businessKey.startsWith(ORIGINAL_BUSINESS_KEY_PREFIX)) {
            // The twin's own activities, and anything started outside launchProcess, are not
            // bridge triggers. Deliberately silent: on a normal run this is the majority of events.
            return;
        }
        String twinId = businessKey.substring(ORIGINAL_BUSINESS_KEY_PREFIX.length());
        String activityId = event.getCurrentActivityId();

        // Handing off to another thread is the point of this method, not an optimisation.
        // AFTER_COMMIT on its own is not enough: Spring runs it while the committed transaction's
        // resources are still bound to this thread (measured: isActualTransactionActive() is still
        // true here, the DataSource ConnectionHolder is unbound later, in cleanupAfterCompletion).
        // So a same-thread bridgeActivityEvent would open PROPAGATION_REQUIRED commands that JOIN a
        // transaction that has already committed -- its writes are never committed by any
        // transaction manager, and only survive by accident, when restoring autoCommit on the
        // connection implicitly commits them. It would also hold that connection across the node
        // manager HTTP call. The worker thread gets a real, independent transaction and its own
        // connection (measured: isActualTransactionActive() is false there).
        Future<?> bridged = bridgeExecutor.submit(() -> runBridge(twinId, activityId));

        // Waited on rather than fire-and-forget so the caller's own read-back is deterministic:
        // the UI refetches the twin's event log immediately after "Complete current task(s)", and
        // that log is the artifact the whole feature is demonstrated through. The wait costs
        // nothing in DB-lock terms (this thread's transaction is already committed) and degrades to
        // a logged timeout, never to a lost or half-applied bridge.
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
            // Expected on a normal run, not an error worth shouting about: every activity of the
            // original raises this, including ones nobody connected, and the first few activities
            // of a launch fire before launchProcess has finished registering the TwinProcess.
            // bridgeActivityEvent already logs the outcomes that are actually interesting.
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        bridgeExecutor.shutdownNow();
    }
}
