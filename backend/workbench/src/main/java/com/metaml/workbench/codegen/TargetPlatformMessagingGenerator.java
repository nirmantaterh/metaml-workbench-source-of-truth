package com.metaml.workbench.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

// Generates the RabbitMQ-backed synchronization layer for a RedCollarTP-derived Target Platform:
// proxy and twin are two independently deployable process definitions in the SAME generated app,
// and the only place their BPMNs actually meet is a shared signal name - neither throws it, both
// wait on it (see TargetPlatformSourceGenerator's own "(MSG)" event handling). Something external
// has to deliver that signal to both sides once they're genuinely ready, which is exactly what
// SignalBroadcaster + the queue pair per shared signal below do, over a real broker rather than an
// in-process call - so a proxy and twin that end up on physically separate deployments still
// synchronize the same way.
//
// This is a deliberately simplified port of the equivalent mechanism the older camundademo-based
// Target Harness Platform generates (see SpringBootProjectGenerator.writeRabbitMqMessaging /
// writeSignalBroadcaster / writePairRegistry): that version routes through a Twin EXTERNAL-TASK
// TOPIC because that template's Twin activities are external tasks. RedCollarTP's activities are
// plain camunda:delegateExpression beans with no topic of their own, so this version keys every
// queue directly by BPMN signal name instead - one fewer layer of indirection, nothing else about
// the proven REQUEST/RESPONSE rendezvous algorithm changes.
@Component
public class TargetPlatformMessagingGenerator {

    public record GeneratedSource(String relativeDirectory, String className, String source) { }

    // messagingNamespace scopes queue/exchange names so two independently generated projects can
    // never physically share a queue even with identical signal names (mirrors the older
    // pipeline's own messagingNamespace = process-key-slug + generated projectId).
    // sharedSignalNames: present in BOTH proxy and twin - real Main<->Twin sync points, each gets
    // its own task+response queue pair. allSignalNames: every signal named in EITHER BPMN -
    // SignalBroadcaster polls all of them; one declared on only one side (RedCollar's own
    // Manuf-only orderVerifySignal) simply has no partner and falls back to direct delivery, same
    // as the older pipeline's own fallback.
    public List<GeneratedSource> generate(String messagingNamespace, Set<String> sharedSignalNames,
            Set<String> allSignalNames, String proxyProcessKey, String twinProcessKey) {
        List<GeneratedSource> sources = new ArrayList<>();
        sources.add(pairRegistry());
        sources.addAll(messaging(messagingNamespace, sharedSignalNames));
        sources.add(signalBroadcaster(allSignalNames));
        sources.add(proxyController(proxyProcessKey));
        sources.add(twinController(twinProcessKey));
        return sources;
    }

    private static String escapeJavaStringLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Same kebab-case convention the older pipeline's own queue names use, restricted to safe
    // RabbitMQ identifier characters - see SpringBootProjectGenerator's own two sibling helpers of
    // the same name for the reasoning; this is a direct copy since signal names are exactly the
    // same kind of author-controlled BPMN identifier those methods were written to sanitize.
    private static String slug(String raw) {
        String withHyphens = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .toLowerCase();
        String cleaned = withHyphens.replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "signal";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private GeneratedSource pairRegistry() {
        String source = """
                package com.tp.TargetPlatform.coordination;

                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.ConcurrentMap;

                import org.springframework.stereotype.Component;

                // Pairs a proxy instance with its twin by the caller-supplied business key both /start
                // endpoints accept - no BPMN-specific knowledge. The first process instance to register a
                // given business key is the "initiator" (proxy, in this generated platform's own usage);
                // the next instance to register the SAME key is the "responder" (twin). A business key is
                // pairing/correlation data only, not the communication mechanism itself - see
                // SignalBroadcaster for how these roles turn each shared signal into a real, targeted
                // proxy -> twin -> proxy handoff instead of an undifferentiated broadcast.
                @Component
                public class PairRegistry {

                    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
                    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

                    // Returns "initiator" for the first instance registered under businessKey, "responder"
                    // for the second, and null for a blank key or a third-or-later instance sharing an
                    // already-claimed key - unpaired, callers fall back to their own default behavior.
                    public String registerAndClassify(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.putIfAbsent(businessKey, processInstanceId);
                        if (initiator == null || initiator.equals(processInstanceId)) {
                            return "initiator";
                        }
                        String responder = responders.putIfAbsent(businessKey, processInstanceId);
                        if (responder == null || responder.equals(processInstanceId)) {
                            return "responder";
                        }
                        return null;
                    }

                    // The other half of the pair for this business key, or null if unpaired.
                    public String partnerOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.get(businessKey);
                        String responder = responders.get(businessKey);
                        if (processInstanceId.equals(initiator)) {
                            return responder;
                        }
                        if (processInstanceId.equals(responder)) {
                            return initiator;
                        }
                        return null;
                    }

                    public String roleOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        if (processInstanceId.equals(initiators.get(businessKey))) {
                            return "initiator";
                        }
                        if (processInstanceId.equals(responders.get(businessKey))) {
                            return "responder";
                        }
                        return null;
                    }
                }
                """;
        return new GeneratedSource("coordination", "PairRegistry", source);
    }

    private record SignalQueue(String signal, String taskQueue, String taskRoutingKey, String responseQueue,
            String responseRoutingKey, String javaIdentifier) { }

    private static List<SignalQueue> assignSignalQueues(String messagingNamespace, Set<String> sharedSignalNames) {
        List<SignalQueue> queues = new ArrayList<>();
        java.util.Set<String> usedSlugs = new java.util.HashSet<>();
        int index = 0;
        for (String signal : sharedSignalNames) {
            String base = slug(signal);
            String candidate = base;
            int suffix = 2;
            while (!usedSlugs.add(candidate)) {
                candidate = base + "-" + suffix++;
            }
            String taskQueue = messagingNamespace + ".sync." + candidate;
            String responseQueue = messagingNamespace + ".sync.responses." + candidate;
            queues.add(new SignalQueue(signal, taskQueue, "sync." + candidate, responseQueue,
                    "sync.responses." + candidate, "q" + (index++) + "_" + candidate.replace('-', '_')));
        }
        return queues;
    }

    private List<GeneratedSource> messaging(String messagingNamespace, Set<String> sharedSignalNames) {
        List<SignalQueue> queues = assignSignalQueues(messagingNamespace, sharedSignalNames);
        String exchangeName = messagingNamespace + ".exchange";

        String taskEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.taskQueue() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.responseQueue() + "\")")
                .collect(Collectors.joining(",\n            "));
        String taskRoutingEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.taskRoutingKey() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseRoutingEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.responseRoutingKey()
                        + "\")")
                .collect(Collectors.joining(",\n            "));
        String queueBeans = queues.stream()
                .map(q -> """

                        @Bean
                        public Queue %1$sTaskQueue() {
                            return new Queue("%2$s");
                        }

                        @Bean
                        public Binding %1$sTaskBinding() {
                            return BindingBuilder.bind(%1$sTaskQueue()).to(syncExchange()).with("%3$s");
                        }

                        @Bean
                        public Queue %1$sResponseQueue() {
                            return new Queue("%4$s");
                        }

                        @Bean
                        public Binding %1$sResponseBinding() {
                            return BindingBuilder.bind(%1$sResponseQueue()).to(syncExchange()).with("%5$s");
                        }
                        """.formatted(q.javaIdentifier(), q.taskQueue(), q.taskRoutingKey(), q.responseQueue(),
                        q.responseRoutingKey()))
                .collect(Collectors.joining());

        String configSource = """
                package com.tp.TargetPlatform.messaging;

                import java.util.Map;

                import org.springframework.amqp.core.Binding;
                import org.springframework.amqp.core.BindingBuilder;
                import org.springframework.amqp.core.DirectExchange;
                import org.springframework.amqp.core.Queue;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                // RabbitMQ topology for this generated platform's proxy<->twin synchronization: one task
                // queue and one response queue per shared BPMN signal (see
                // TargetPlatformMessagingGenerator.assignSignalQueues), scoped to this generated project so
                // two independently generated platforms can never physically share a queue. Enabled only
                // with metaml.messaging.enabled=true.
                @Configuration
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class RabbitMqConfig {

                    public static final String EXCHANGE = "%s";

                    // Shared signal name -> its dedicated task queue (proxy asks twin to advance past
                    // this signal). The single source of truth for which signals have RabbitMQ queues at
                    // all - a signal absent from this map exists on only one side and is delivered
                    // directly instead (see SignalBroadcaster.deliverTo).
                    public static final Map<String, String> TASK_QUEUE_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    // Shared signal name -> its dedicated response queue (twin reports it advanced).
                    public static final Map<String, String> RESPONSE_QUEUE_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    public static final Map<String, String> TASK_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    public static final Map<String, String> RESPONSE_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    @Bean
                    public DirectExchange syncExchange() {
                        return new DirectExchange(EXCHANGE);
                    }
                    %s
                }
                """.formatted(exchangeName, taskEntries, responseEntries, taskRoutingEntries, responseRoutingEntries,
                queueBeans);

        String taskPublisherSource = """
                package com.tp.TargetPlatform.messaging;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                // Publishes "proxy is ready to advance past this signal" to that signal's own dedicated
                // task queue. TaskQueueListener performs the actual Camunda signal delivery that releases
                // twin's waiting execution, on consume. Always present as a bean, but isEnabled() is false
                // unless metaml.messaging.enabled=true.
                @Component
                public class TaskQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(TaskQueuePublisher.class);

                    private final RabbitTemplate rabbitTemplate;
                    private final boolean enabled;

                    public TaskQueuePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
                        this.rabbitTemplate = rabbitTemplate;
                        this.enabled = enabled;
                    }

                    public boolean isEnabled() {
                        return enabled;
                    }

                    public boolean isEligible(String signalName) {
                        return RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
                    }

                    public void publish(String signalName, String executionId, String processInstanceId,
                            String businessKey) {
                        String routingKey = RabbitMqConfig.TASK_ROUTING_KEY_BY_SIGNAL.get(signalName);
                        if (routingKey == null) {
                            throw new IllegalArgumentException("No task queue is declared for signal '"
                                    + signalName + "' - callers must check isEligible(signalName) first");
                        }
                        String payload = signalName + "|" + executionId + "|" + processInstanceId
                                + "|" + (businessKey == null ? "" : businessKey);
                        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload);
                        logger.info("TASK: published signal '{}' to RabbitMQ exchange '{}' key '{}' for "
                                + "execution {} (processInstanceId={}, businessKey={})", signalName,
                                RabbitMqConfig.EXCHANGE, routingKey, executionId, processInstanceId, businessKey);
                    }
                }
                """;

        String responsePublisherSource = """
                package com.tp.TargetPlatform.messaging;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                // Publishes "twin has advanced past this signal" to that signal's own dedicated response
                // queue. ResponseQueueListener performs the actual Camunda signal delivery that releases
                // proxy's waiting execution, on consume.
                @Component
                public class ResponseQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(ResponseQueuePublisher.class);

                    private final RabbitTemplate rabbitTemplate;
                    private final boolean enabled;

                    public ResponseQueuePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
                        this.rabbitTemplate = rabbitTemplate;
                        this.enabled = enabled;
                    }

                    public boolean isEnabled() {
                        return enabled;
                    }

                    public boolean isEligible(String signalName) {
                        return RabbitMqConfig.RESPONSE_QUEUE_BY_SIGNAL.containsKey(signalName);
                    }

                    public void publish(String signalName, String executionId, String processInstanceId,
                            String businessKey) {
                        String routingKey = RabbitMqConfig.RESPONSE_ROUTING_KEY_BY_SIGNAL.get(signalName);
                        if (routingKey == null) {
                            throw new IllegalArgumentException("No response queue is declared for signal '"
                                    + signalName + "' - callers must check isEligible(signalName) first");
                        }
                        String payload = signalName + "|" + executionId + "|" + processInstanceId
                                + "|" + (businessKey == null ? "" : businessKey);
                        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload);
                        logger.info("RESPONSE: published signal '{}' to RabbitMQ exchange '{}' key '{}' for "
                                + "execution {} (processInstanceId={}, businessKey={})", signalName,
                                RabbitMqConfig.EXCHANGE, routingKey, executionId, processInstanceId, businessKey);
                    }
                }
                """;

        boolean hasQueues = !queues.isEmpty();
        String listenerImports = hasQueues
                ? """
                        import org.camunda.bpm.engine.RuntimeService;
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;
                        import org.springframework.amqp.rabbit.annotation.RabbitListener;
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """
                : """
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """;
        String noQueuesComment = "    // No signal is shared between proxy and twin in this project's BPMNs, "
                + "so there is\n    // nothing to consume here.\n";

        String taskListenerFields = hasQueues
                ? "    private static final Logger logger = LoggerFactory.getLogger(TaskQueueListener.class);\n\n"
                : "";
        String taskListenerBody = hasQueues
                ? """
                            private final RuntimeService runtimeService;

                            public TaskQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            @RabbitListener(queues = { %s })
                            public void onTaskMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
                                if (parts.length != 4) {
                                    logger.error("[task-queue] discarding malformed message: {}", payload);
                                    return;
                                }
                                String signalName = parts[0];
                                String executionId = parts[1];
                                String processInstanceId = parts[2];
                                String businessKey = parts[3];
                                try {
                                    runtimeService.signalEventReceived(signalName, executionId);
                                    logger.info("TASK: delivered signal '{}' to execution {} (processInstanceId={}, "
                                            + "businessKey={}) via RabbitMQ", signalName, executionId,
                                            processInstanceId, businessKey);
                                } catch (Exception e) {
                                    logger.info("TASK: signal '{}' delivery to execution {} skipped (already "
                                            + "advanced?): {}", signalName, executionId, e.toString());
                                }
                            }
                        """.formatted(queues.stream().map(q -> "\"" + q.taskQueue() + "\"")
                        .collect(Collectors.joining(", ")))
                : noQueuesComment;

        String responseListenerFields = hasQueues
                ? "    private static final Logger logger = LoggerFactory.getLogger(ResponseQueueListener.class);\n\n"
                : "";
        String responseListenerBody = hasQueues
                ? """
                            private final RuntimeService runtimeService;

                            public ResponseQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            @RabbitListener(queues = { %s })
                            public void onResponseMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
                                if (parts.length != 4) {
                                    logger.error("[response-queue] discarding malformed message: {}", payload);
                                    return;
                                }
                                String signalName = parts[0];
                                String executionId = parts[1];
                                String processInstanceId = parts[2];
                                String businessKey = parts[3];
                                try {
                                    runtimeService.signalEventReceived(signalName, executionId);
                                    logger.info("RESPONSE: delivered signal '{}' to execution {} "
                                            + "(processInstanceId={}, businessKey={}) via RabbitMQ", signalName,
                                            executionId, processInstanceId, businessKey);
                                } catch (Exception e) {
                                    logger.info("RESPONSE: signal '{}' delivery to execution {} skipped (already "
                                            + "advanced?): {}", signalName, executionId, e.toString());
                                }
                            }
                        """.formatted(queues.stream().map(q -> "\"" + q.responseQueue() + "\"")
                        .collect(Collectors.joining(", ")))
                : noQueuesComment;

        String taskListenerSource = """
                package com.tp.TargetPlatform.messaging;

                %s
                // The real consumer for task messages - the Camunda signal delivery that releases twin's
                // waiting execution happens here, triggered by consuming the message. Enabled only with
                // metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly
                // instead.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class TaskQueueListener {

                %s%s
                }
                """.formatted(listenerImports, taskListenerFields, taskListenerBody);

        String responseListenerSource = """
                package com.tp.TargetPlatform.messaging;

                %s
                // The real consumer for response messages - the Camunda signal delivery that releases
                // proxy's waiting execution happens here, triggered by consuming the message.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class ResponseQueueListener {

                %s%s
                }
                """.formatted(listenerImports, responseListenerFields, responseListenerBody);

        return List.of(
                new GeneratedSource("messaging", "RabbitMqConfig", configSource),
                new GeneratedSource("messaging", "TaskQueuePublisher", taskPublisherSource),
                new GeneratedSource("messaging", "TaskQueueListener", taskListenerSource),
                new GeneratedSource("messaging", "ResponseQueuePublisher", responsePublisherSource),
                new GeneratedSource("messaging", "ResponseQueueListener", responseListenerSource));
    }

    private GeneratedSource signalBroadcaster(Set<String> allSignalNames) {
        String signalList = allSignalNames.stream()
                .map(s -> "\"" + escapeJavaStringLiteral(s) + "\"")
                .collect(Collectors.joining(", "));

        String source = """
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

                // Delivers BPMN-defined signals to the specific executions currently waiting on each one,
                // so proxy's and twin's signal catch events can both advance. Neither throws its own
                // signals, so delivery has to happen externally - this is that external driver. Ported
                // from the equivalent mechanism in the older camundademo-based Target Harness Platform
                // (see SpringBootProjectGenerator.writeSignalBroadcaster's own, more detailed comment) with
                // one simplification: RedCollarTP has no external-task topic to route through, so a shared
                // signal's own name is directly what RabbitMqConfig's queues are keyed by.
                //
                // For a paired proxy+twin (same business key - see PairRegistry and the generated /start
                // endpoints), each shared signal becomes a genuine two-step, targeted handoff instead of an
                // undifferentiated broadcast:
                //   1. REQUEST (proxy -> twin): once both sides of a pair are simultaneously waiting on the
                //      same signal, only twin's execution is released.
                //   2. RESPONSE (twin -> proxy): proxy's execution is deliberately left waiting until twin
                //      is observed to have moved on - subscribed to a different signal, or completed
                //      entirely - proving its gated activity actually ran, not merely that the signal
                //      arrived. Only then is proxy's execution released.
                // A signal declared on only one side, or whose partner is not currently waiting on it
                // (unpaired, or a rework-loop revisit), is delivered to immediately instead - this is what
                // lets a lone proxy instance (no twin started) still run to completion.
                @Component
                public class SignalBroadcaster {

                    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
                    private static final List<String> SIGNAL_NAMES = List.of(%s);

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;
                    private final TaskQueuePublisher taskQueuePublisher;
                    private final ResponseQueuePublisher responseQueuePublisher;
                    private final Set<String> awaitingResponse = ConcurrentHashMap.newKeySet();
                    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
                    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
                    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;

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
                                deliverTo(signalName, subscription, businessKey, "RESPONSE");
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

                    // True once the responder has provably moved past the gated task behind signalName -
                    // subscribed to a different signal, or completed entirely - rather than merely having
                    // received the signal itself, which happens before its gated task ever runs.
                    //
                    // Relies on the responder's own JavaDelegate.execute() running synchronously, inside
                    // the same Camunda command/transaction as the signal delivery that triggers it - only
                    // that makes "no longer subscribed to signalName" (checked here via a separate query,
                    // on a later broadcaster tick) proof that the gated task actually finished, rather than
                    // merely that it started. A delegate that hands work to another thread and returns
                    // early would make this method return true before the real work is done.
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
                """.formatted(signalList);
        return new GeneratedSource("signal", "SignalBroadcaster", source);
    }

    // Overwrites the template's placeholder ProxyProcessController (health-check only) with a real
    // /start (registers with PairRegistry so SignalBroadcaster can pair it with its twin) and
    // /instances listing - the prerequisite for there being anything to synchronize or watch in
    // Cockpit at all. processKey is baked in as a literal because this generated project deploys
    // exactly one proxy definition per generation (see SpringBootProjectGenerator.generateTargetPlatform).
    private GeneratedSource proxyController(String processKey) {
        return sideController("proxy", "ProxyProcessController", "/api/proxy", processKey);
    }

    private GeneratedSource twinController(String processKey) {
        return sideController("twin", "TwinProcessController", "/api/twin", processKey);
    }

    private GeneratedSource sideController(String side, String className, String mapping, String processKey) {
        String source = """
                package com.tp.TargetPlatform.%1$s.controller;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.UUID;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                import com.tp.TargetPlatform.coordination.PairRegistry;

                // Starts and registers %2$s process instances. businessKey is what pairs a %2$s instance
                // with its counterpart (see PairRegistry / SignalBroadcaster) - the first instance
                // registered under a key is the initiator, the second is the responder, so starting a
                // proxy and a twin with the SAME businessKey is what makes them synchronize.
                @RestController
                @RequestMapping("%3$s")
                public class %4$s {

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;

                    public %4$s(RuntimeService runtimeService, PairRegistry pairRegistry) {
                        this.runtimeService = runtimeService;
                        this.pairRegistry = pairRegistry;
                    }

                    @GetMapping("/health")
                    public String health() {
                        return "%1$s ok";
                    }

                    // businessKey is optional - omit it to run a lone %1$s instance with nothing to
                    // synchronize against (every signal falls back to immediate delivery); supply the
                    // SAME key on both sides' /start calls to pair them.
                    @PostMapping("/start")
                    public Map<String, Object> start(@RequestParam(required = false) String businessKey) {
                        String key = (businessKey == null || businessKey.isBlank())
                                ? UUID.randomUUID().toString() : businessKey;
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%5$s", key);
                        String role = pairRegistry.registerAndClassify(key, instance.getProcessInstanceId());
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceId", instance.getProcessInstanceId());
                        body.put("businessKey", key);
                        body.put("role", role == null ? "unpaired" : role);
                        return body;
                    }
                }
                """.formatted(side, side, mapping, className, processKey);
        return new GeneratedSource(side + "/controller", className, source);
    }
}
