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

// Generates one external-task worker per camunda:topic, the counterpart to DelegateClassGenerator
// for delegateExpression tasks. A serviceTask carrying camunda:type="external" is a wait state the
// engine never runs on its own (see BpmnActivities); without a worker subscribed to its topic the
// token parks there forever. RedCollar's processes are built entirely from external tasks, so with
// no worker generation the generated platform deploys but nothing executes.
//
// Workers use the embedded engine's ExternalTaskService API directly (fetchAndLock + complete),
// not the external-task client Spring Boot starter, because the client starter depends on the
// Camunda REST starter which requires Jersey — incompatible with Spring Boot 4.x. In-process
// workers are also simpler and faster: no HTTP round-trip, no port configuration, no startup
// race between server and client.
//
// simulateMlAgent decides whether the worker invokes a simulated agent boundary. Twin workers
// call simulateAgentInvocation(), which produces runtime data — a unique invocation ID and
// timestamp — that varies per execution, and complete the task with that data as process
// variables. Manufacturing workers just log execution and complete. Neither is RedCollar-specific:
// both read topics straight from the model, and the flag is the caller's.
//
// Gateway variable detection: when an external task immediately precedes an exclusive gateway
// whose condition references a process variable, the generated worker must set that variable or
// the gateway throws PropertyNotFoundException. The value is non-deterministic (Math.random()),
// not a predetermined business outcome.
@Component
public class ExternalTaskWorkerGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";
    private static final Pattern CONDITION_VAR_PATTERN = Pattern.compile("\\$\\{!?(\\w+)\\}");

    // One worker per unique topic, in document order. Two tasks sharing a topic share the one
    // subscription at runtime, so generating two classes for it would just be two beans fighting
    // over the same topic - deduped by topic for the same reason DelegateClassGenerator dedups by
    // className.
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

    // Finds process variables referenced in exclusive-gateway conditions and maps them back to the
    // external-task topic whose worker must set them. Only direct predecessors are traced: if the
    // incoming flow's source is an external-task activity, its topic gets the variable. Intermediate
    // elements (catches, other tasks) are not followed — gateway variables in those cases need
    // different handling anyway.
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

    // topics are conventionally already valid Java identifiers (e.g. SamplingTwin), but this is
    // user-authored BPMN - a stray character shouldn't produce a .java file that fails to compile.
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
                ? renderTwinWorkerSource(packageName, className, topic, label)
                : renderPlainWorkerSource(packageName, className, topic, label, gatewayVars);
    }

    // Twin workers invoke a simulated agent boundary that produces unique runtime data per
    // execution, then complete the external task with that data as process variables.
    private static String renderTwinWorkerSource(String packageName, String className, String topic, String label) {
        // Derive the worker base package for the GeneratedExternalTaskWorker import
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.UUID;

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
                        logger.info("[Twin] Invoking simulated ML agent for activity \\"%3$s\\" "
                                + "(process instance {})", task.getProcessInstanceId());
                        Map<String, Object> agentResult = simulateAgentInvocation(task);
                        logger.info("[Twin] Agent invocation result: {}", agentResult);
                        externalTaskService.complete(task.getId(), "generated-worker", agentResult);
                    }

                    // Simulated agent invocation boundary. Produces runtime data that varies per
                    // execution rather than a predetermined outcome. Replace this method body with
                    // real agent/ML integration when ready.
                    private Map<String, Object> simulateAgentInvocation(LockedExternalTask task) {
                        Map<String, Object> output = new HashMap<>();
                        output.put("agentTopic", task.getTopicName());
                        output.put("agentInvocationId", UUID.randomUUID().toString());
                        output.put("agentTimestamp", System.currentTimeMillis());
                        return output;
                    }
                }
                """.formatted(packageName, topic, label, className, workerBasePackage);
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

        // Workers that precede exclusive gateways must set the condition variables or the
        // gateway throws PropertyNotFoundException. Values are non-deterministic (Math.random),
        // not a predetermined business outcome — the BPMN responds to whatever the runtime
        // produces, and the process may take either branch on any given execution.
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
