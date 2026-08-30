package com.tp.TargetPlatform.signal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.coordination.PairRegistry;
import com.tp.TargetPlatform.messaging.RabbitMqConfig;
import com.tp.TargetPlatform.messaging.TaskQueuePublisher;
import com.tp.TargetPlatform.messaging.ResponseQueuePublisher;

// Delivers BPMN-defined signals to the specific executions currently waiting on each one, so proxy's and twin's signal catch events can both advance. Neither throws its own signals, so delivery has to happen externally - this is that external driver. Ported from the equivalent mechanism in the older camundademo-based Target Harness Platform (see SpringBootProjectGenerator.writeSignalBroadcaster's own, more detailed comment) with one simplification: RedCollarTP has no external-task topic to route through, so a shared signal's own name is directly what RabbitMqConfig's queues are keyed by. For a paired proxy+twin (same business key - see PairRegistry and the generated /start endpoints), each shared signal becomes a genuine two-step, targeted handoff instead of an undifferentiated broadcast: 1. REQUEST (proxy -> twin): once both sides of a pair are simultaneously waiting on the same signal, only twin's execution is released. 2. RESPONSE (twin -> proxy): proxy's execution is deliberately left waiting until twin is observed to have moved on - subscribed to a different signal, or completed entirely - proving its gated activity actually ran, not merely that the signal arrived. Only then is proxy's execution released. A signal declared on only one side, or whose partner is not currently waiting on it (unpaired, or a rework-loop revisit), is delivered to immediately instead - this is what lets a lone proxy instance (no twin started) still run to completion.
@Component
public class SignalBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
    private static final List<String> SIGNAL_NAMES = List.of("samplingSignal", "layingSignal", "markingSignal", "cuttingSignal", "stitchingSignal", "checkingSignal", "pressingSignal", "packagingSignal", "shippingSignal", "Signal_3733ues", "orderVerifySignal");

    private final RuntimeService runtimeService;
    private final PairRegistry pairRegistry;
    private final TaskQueuePublisher taskQueuePublisher;
    private final ResponseQueuePublisher responseQueuePublisher;
    private final Set<String> awaitingResponse = ConcurrentHashMap.newKeySet();
    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;
    // Reliability hardening (Pass 2): handoffKeys already logged as stuck-on-a-failed- partner, so the ERROR log below fires once per stuck period rather than once per second for as long as the incident is open. Cleared alongside awaitingResponse's own removal (whether the eventual outcome is a genuine advance or the handoff simply ending some other way) so a LATER stall on the same handoffKey logs again.
    private final Set<String> stuckOnIncidentLogged = ConcurrentHashMap.newKeySet();

    public SignalBroadcaster(RuntimeService runtimeService, PairRegistry pairRegistry,
            TaskQueuePublisher taskQueuePublisher, ResponseQueuePublisher responseQueuePublisher) {
        this.runtimeService = runtimeService;
        this.pairRegistry = pairRegistry;
        this.taskQueuePublisher = taskQueuePublisher;
        this.responseQueuePublisher = responseQueuePublisher;
    }

    @Scheduled(fixedDelay = 1000)
    public void broadcastSignals() {
        for (String signalName : SIGNAL_NAMES) {
            List<EventSubscription> waiting = runtimeService.createEventSubscriptionQuery()
                    .eventType("signal")
                    .eventName(signalName)
                    .list();
            for (EventSubscription subscription : waiting) {
                handle(signalName, subscription, waiting);
            }
        }
    }

    private void handle(String signalName, EventSubscription subscription,
            List<EventSubscription> waitingForSameSignal) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(subscription.getProcessInstanceId())
                .singleResult();
        String businessKey = instance == null ? null : instance.getBusinessKey();
        String role = pairRegistry.roleOf(businessKey, subscription.getProcessInstanceId());
        String partnerInstanceId = pairRegistry.partnerOf(businessKey, subscription.getProcessInstanceId());

        if (role == null || partnerInstanceId == null) {
            deliverTo(signalName, subscription, businessKey, "DELIVERED");
            return;
        }
        boolean partnerWaitingNow = waitingForSameSignal.stream()
                .anyMatch(s -> s.getProcessInstanceId().equals(partnerInstanceId));
        String waitKey = subscription.getProcessInstanceId() + "|" + signalName;

        if ("responder".equals(role)) {
            if (partnerWaitingNow) {
                partnerArrivalTicks.remove(waitKey);
            } else if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                deliverTo(signalName, subscription, businessKey, "DELIVERED");
            }
            return;
        }

        String handoffKey = businessKey + "|" + signalName;
        if (awaitingResponse.contains(handoffKey)) {
            if (responderHasAdvancedPast(signalName, partnerInstanceId)) {
                awaitingResponse.remove(handoffKey);
                stuckOnIncidentLogged.remove(handoffKey);
                deliverTo(signalName, subscription, businessKey, "RESPONSE");
            } else {
                // Reliability hardening (Pass 2): unlike partnerNotComing (bounded, self-releasing - the responder legitimately may not have arrived yet), this wait has no such bound, because there is no safe fallback here - the Twin's gated activity may genuinely still be running, and releasing the Proxy without proof it finished is exactly the "pretend completion" this broadcaster must never do. What CAN be told apart, using real Camunda state rather than an invented timeout, is "still legitimately in progress" from "provably stuck" - a Camunda incident (job retries exhausted, or a failed external task) on the responder's own process instance means it will NOT resolve on its own. Logging that once makes an otherwise silent, indefinite wait observable instead of indistinguishable from a merely slow partner; the proxy still does not advance - only a genuine, later responderHasAdvancedPast()==true (the incident gets resolved and the responder's execution actually moves on) does that.
                boolean partnerHasOpenIncident = runtimeService.createIncidentQuery()
                        .processInstanceId(partnerInstanceId).count() > 0;
                if (partnerHasOpenIncident) {
                    // add() itself is what makes this fire only the FIRST tick an incident is observed for this handoffKey - checking the incident BEFORE calling add() (rather than relying on add()'s own return value to short- circuit the query) is what keeps a legitimately-slow, incident-free tick from ever marking this handoffKey "already logged".
                    if (stuckOnIncidentLogged.add(handoffKey)) {
                        logger.error("STUCK: proxy execution {} (businessKey={}) is waiting on "
                                + "RESPONSE for signal '{}', but its twin partner "
                                + "(processInstanceId={}) has an open Camunda incident and will "
                                + "not advance on its own - this handoff will not complete until "
                                + "that incident is resolved. The proxy has NOT been advanced.",
                                subscription.getExecutionId(), businessKey, signalName,
                                partnerInstanceId);
                    }
                } else {
                    // No incident currently open (never had one, or a prior one was already resolved) - clear any stale suppression so a LATER incident on this same handoffKey logs again instead of staying silenced forever.
                    stuckOnIncidentLogged.remove(handoffKey);
                }
            }
            return;
        }

        if (partnerWaitingNow) {
            partnerArrivalTicks.remove(waitKey);
            EventSubscription responderSubscription = waitingForSameSignal.stream()
                    .filter(s -> s.getProcessInstanceId().equals(partnerInstanceId))
                    .findFirst()
                    .orElse(null);
            if (responderSubscription != null) {
                deliverTo(signalName, responderSubscription, businessKey, "REQUEST");
                awaitingResponse.add(handoffKey);
            }
            return;
        }

        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
            deliverTo(signalName, subscription, businessKey, "DELIVERED");
        }
    }

    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        return false;
    }

    // True once the responder has provably moved past the gated task behind signalName - subscribed to a different signal, or completed entirely - rather than merely having received the signal itself, which happens before its gated task ever runs. Relies on the responder's own JavaDelegate.execute() running synchronously, inside the same Camunda command/transaction as the signal delivery that triggers it - only that makes "no longer subscribed to signalName" (checked here via a separate query, on a later broadcaster tick) proof that the gated task actually finished, rather than merely that it started. A delegate that hands work to another thread and returns early would make this method return true before the real work is done.
    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId) {
        ProcessInstance stillActive = runtimeService.createProcessInstanceQuery()
                .processInstanceId(responderInstanceId)
                .singleResult();
        if (stillActive == null) {
            return true;
        }
        List<EventSubscription> responderSignals = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(responderInstanceId)
                .eventType("signal")
                .list();
        boolean stillOnSameSignal = responderSignals.stream()
                .anyMatch(s -> s.getEventName().equals(signalName));
        if (stillOnSameSignal) {
            return false;
        }
        return !responderSignals.isEmpty();
    }

    private void deliverTo(String signalName, EventSubscription subscription, String businessKey,
            String phase) {
        boolean gated = RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
        if (gated) {
            if ("REQUEST".equals(phase) && taskQueuePublisher.isEnabled()
                    && taskQueuePublisher.isEligible(signalName)) {
                taskQueuePublisher.publish(signalName, subscription.getExecutionId(),
                        subscription.getProcessInstanceId(), businessKey);
                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                return;
            }
            if ("RESPONSE".equals(phase) && responseQueuePublisher.isEnabled()
                    && responseQueuePublisher.isEligible(signalName)) {
                responseQueuePublisher.publish(signalName, subscription.getExecutionId(),
                        subscription.getProcessInstanceId(), businessKey);
                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                return;
            }
        }
        try {
            runtimeService.signalEventReceived(signalName, subscription.getExecutionId());
            everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
            logger.info("{}: delivered signal '{}' to execution {} (processInstanceId={}, "
                    + "businessKey={})", phase, signalName, subscription.getExecutionId(),
                    subscription.getProcessInstanceId(), businessKey);
        } catch (Exception e) {
            // Expected during normal operation - the execution may already have advanced.
        }
    }
}
