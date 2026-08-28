package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Generates one external-task worker per camunda:topic, the counterpart to DelegateClassGenerator for delegateExpression tasks. A serviceTask carrying camunda:type="external" is a wait state the engine never runs on its own (see BpmnActivities); without a worker subscribed to its topic the token parks there forever. RedCollar's processes are built entirely from external tasks, so with no worker generation the generated platform deploys but nothing executes. Workers use the embedded engine's ExternalTaskService API directly (fetchAndLock + complete), not the external-task client Spring Boot starter, because the client starter depends on the Camunda REST starter which requires Jersey — incompatible with Spring Boot 4.x. In-process workers are also simpler and faster: no HTTP round-trip, no port configuration, no startup race between server and client. simulateMlAgent decides whether the worker delegates its decision to a TwinDecisionAgent bean (see SpringBootProjectGenerator.writeTwinDecisionAgentInterface) instead of just logging and completing. Every generated Twin worker takes one in its constructor and calls decide(topic, task) for its completion variables - which is what makes the twin side pluggable: register your own @Component implementing TwinDecisionAgent (a real risk model, an ML call, whatever the twin should be mirroring) and Spring wires it in ahead of the generated fallback with no generated code to touch. Manufacturing (proxy) workers just log execution and complete - they're driven by the real business systems the process already targets, not by a pluggable decision boundary. Neither is RedCollar-specific: both read topics straight from the model, and the flag is the caller's. Gateway variable detection: when an external task immediately precedes an exclusive gateway whose condition references a process variable, the generated worker must set that variable or the gateway throws PropertyNotFoundException. The value is non-deterministic (Math.random()), not a predetermined business outcome.
@Component
public class ExternalTaskWorkerGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";
    private static final Pattern CONDITION_VAR_PATTERN = Pattern.compile("\\$\\{!?(\\w+)\\}");

    // One worker per unique topic, in document order. Two tasks sharing a topic share the one subscription at runtime, so generating two classes for it would just be two beans fighting over the same topic - deduped by topic for the same reason DelegateClassGenerator dedups by className.
    public List<GeneratedWorker> generate(String bpmnXml, String packageName, boolean simulateMlAgent) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVarsByTopic = detectGatewayVariables(model);

        Map<String, GeneratedWorker> byTopic = new LinkedHashMap<>();
        for (Activity element : model.getModelElementsByType(Activity.class)) {
            if (!EXTERNAL_IMPLEMENTATION.equals(element.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                continue;
            }
            String topic = element.getAttributeValueNs(CAMUNDA_NS, "topic");
            if (topic == null || topic.isBlank() || byTopic.containsKey(topic)) {
                continue;
            }
            String className = toClassName(topic);
            String label = element.getName() == null || element.getName().isBlank()
                    ? topic
                    : sanitizeForComment(element.getName());
            Set<String> gatewayVars = gatewayVarsByTopic.getOrDefault(topic, Set.of());
            byTopic.put(topic, new GeneratedWorker(className, topic,
                    renderSource(packageName, className, topic, label, simulateMlAgent, gatewayVars)));
        }
        return new ArrayList<>(byTopic.values());
    }

    // Finds process variables referenced in exclusive-gateway conditions and maps them back to the external-task topic whose worker must set them. Only direct predecessors are traced: if the incoming flow's source is an external-task activity, its topic gets the variable. Intermediate elements (catches, other tasks) are not followed — gateway variables in those cases need different handling anyway.
    static Map<String, Set<String>> detectGatewayVariables(BpmnModelInstance model) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ExclusiveGateway gw : model.getModelElementsByType(ExclusiveGateway.class)) {
            Set<String> varNames = new LinkedHashSet<>();
            for (SequenceFlow outgoing : gw.getOutgoing()) {
                if (outgoing.getConditionExpression() != null) {
                    String expr = outgoing.getConditionExpression().getTextContent();
                    if (expr != null) {
                        Matcher m = CONDITION_VAR_PATTERN.matcher(expr);
                        while (m.find()) {
                            varNames.add(m.group(1));
                        }
                    }
                }
            }
            if (varNames.isEmpty()) {
                continue;
            }
            for (SequenceFlow incoming : gw.getIncoming()) {
                FlowNode source = incoming.getSource();
                if (source instanceof Activity activity
                        && EXTERNAL_IMPLEMENTATION.equals(activity.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                    String topic = activity.getAttributeValueNs(CAMUNDA_NS, "topic");
                    if (topic != null && !topic.isBlank()) {
                        result.computeIfAbsent(topic, k -> new LinkedHashSet<>()).addAll(varNames);
                    }
                }
            }
        }
        return result;
    }

    // topics are conventionally already valid Java identifiers (e.g. SamplingTwin), but this is user-authored BPMN - a stray character shouldn't produce a .java file that fails to compile.
    private static String toClassName(String topic) {
        StringBuilder sanitized = new StringBuilder(topic.length());
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            boolean valid = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sanitized.append(valid ? c : '_');
        }
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.insert(0, '_');
        }
        sanitized.setCharAt(0, Character.toUpperCase(sanitized.charAt(0)));
        return sanitized.append("Worker").toString();
    }

    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    private static String renderSource(String packageName, String className, String topic, String label,
            boolean simulateMlAgent, Set<String> gatewayVars) {
        return simulateMlAgent
                ? renderTwinWorkerSource(packageName, className, topic, label, gatewayVars)
                : renderPlainWorkerSource(packageName, className, topic, label, gatewayVars);
    }

    // Twin workers delegate their completion variables to an OPTIONALLY injected TwinDecisionAgent (see SpringBootProjectGenerator.writeTwinDecisionAgentInterface) - the pluggable boundary a real model/agent implementation attaches to. ObjectProvider (not a directly injected TwinDecisionAgent, and deliberately not a second @ConditionalOnMissingBean fallback @Component either - that combination silently fails to register: @ConditionalOnMissingBean is only reliably honored inside @Configuration/@AutoConfiguration classes, not on arbitrary component-scanned beans, so with no other implementation on the classpath the "fallback" bean never gets created at all and the app fails to start) is what makes this pluggable AND safe with zero implementations registered: getIfAvailable() returns null cleanly when nobody has wired one in, and returns the single implementation the moment a real @Component providing one exists - no generated code to touch either way. If this topic's activity directly precedes an exclusive gateway (see detectGatewayVariables), the gateway's condition variables must ALSO land in the completion map - the twin side runs its own copy of the same BPMN structure, so the same PropertyNotFoundException risk applies here exactly as it does to the plain (proxy) worker. Rather than require every TwinDecisionAgent implementation to know which topics feed which gateways, the worker itself fills in any gateway variable that's still missing after either path runs, with the same non-deterministic fallback the proxy side uses - a real agent that DOES set the variable simply has that value win.
    private static String renderTwinWorkerSource(String packageName, String className, String topic, String label,
            Set<String> gatewayVars) {
        // Derive the worker base package for the GeneratedExternalTaskWorker import
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        StringBuilder fallbackLines = new StringBuilder();
        for (String varName : gatewayVars) {
            fallbackLines.append("            if (!variables.containsKey(\"").append(varName).append("\")) {\n")
                    .append("                variables.put(\"").append(varName)
                    .append("\", Math.random() > 0.5);\n            }\n");
        }
        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.UUID;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.ObjectProvider;
                import org.springframework.stereotype.Component;

                import %5$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                    private final ObjectProvider<TwinDecisionAgent> agentProvider;

                    public %4$s(ObjectProvider<TwinDecisionAgent> agentProvider) {
                        this.agentProvider = agentProvider;
                    }

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                        TwinDecisionAgent agent = agentProvider.getIfAvailable();
                        Map<String, Object> variables;
                        if (agent != null) {
                            logger.info("[Twin] Invoking decision agent {} for activity \\"%3$s\\" "
                                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
                            variables = new HashMap<>(agent.decide("%2$s", task));
                        } else {
                            // No TwinDecisionAgent registered - built-in fallback. Produces synthetic, runtime-varying output rather than a predetermined business outcome so the twin can still run standalone; register a @Component implementing TwinDecisionAgent to replace this with a real model/agent call.
                            logger.info("[Twin] Invoking simulated ML agent for activity \\"%3$s\\" "
                                    + "(process instance {})", task.getProcessInstanceId());
                            variables = new HashMap<>();
                            variables.put("agentTopic", "%2$s");
                            variables.put("agentInvocationId", UUID.randomUUID().toString());
                            variables.put("agentTimestamp", System.currentTimeMillis());
                            logger.info("[Twin] Agent invocation result: {}", variables);
                        }
                %6$s        logger.info("[Twin] Completion variables: {}", variables);
                        externalTaskService.complete(task.getId(), "generated-worker", variables);
                    }
                }
                """.formatted(packageName, topic, label, className, workerBasePackage, fallbackLines);
    }

    private static String renderPlainWorkerSource(String packageName, String className, String topic, String label,
            Set<String> gatewayVars) {
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        if (gatewayVars.isEmpty()) {
            return """
                    package %1$s;

                    import org.camunda.bpm.engine.ExternalTaskService;
                    import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.stereotype.Component;

                    import %5$s.GeneratedExternalTaskWorker;

                    // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                    @Component
                    public class %4$s implements GeneratedExternalTaskWorker {

                        private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                        @Override
                        public String topic() {
                            return "%2$s";
                        }

                        @Override
                        public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                            logger.info("Executing generated external-task worker for activity \\"%3$s\\" "
                                    + "(process instance {})", task.getProcessInstanceId());
                            externalTaskService.complete(task.getId(), "generated-worker");
                        }
                    }
                    """.formatted(packageName, topic, label, className, workerBasePackage);
        }

        // Workers that precede exclusive gateways must set the condition variables or the gateway throws PropertyNotFoundException. Values are non-deterministic (Math.random), not a predetermined business outcome — the BPMN responds to whatever the runtime produces, and the process may take either branch on any given execution.
        StringBuilder varLines = new StringBuilder();
        for (String varName : gatewayVars) {
            varLines.append("            variables.put(\"").append(varName)
                    .append("\", Math.random() > 0.5);\n");
        }

        String body = "            logger.info(\"Executing generated external-task worker for activity \\\""
                + sanitizeForJavaString(label)
                + "\\\" \"\n                    + \"(process instance {})\", task.getProcessInstanceId());\n"
                + "            Map<String, Object> variables = new HashMap<>();\n"
                + varLines
                + "            logger.info(\"Worker completion variables: {}\", variables);\n"
                + "            externalTaskService.complete(task.getId(), \"generated-worker\", variables);";

        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                import %6$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                %5$s
                    }
                }
                """.formatted(packageName, topic, label, className, body, workerBasePackage);
    }

    private static String sanitizeForJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
