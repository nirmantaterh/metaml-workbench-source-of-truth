package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// New scope item 3 (BPMN Processing): parse a saved model's service tasks and generate a real
// Java Delegate class per delegateExpression, replacing what used to be a person hand-writing
// these. The class name comes from the delegateExpression itself (e.g.
// delegateExpression="${calculateInterestService}" -> class CalculateInterestService), not from
// the task's display name - the expression is what Camunda actually looks up at runtime to find
// the bean, so deriving from it is the only choice that's guaranteed to produce a class the
// generated app can actually run. Deriving from the display name instead risks exactly the
// mismatch the example that kicked this off already showed ("Calculate Interest" next to
// delegateExpression "calculateInterestService" - different casing, different wording).
@Component
public class DelegateClassGenerator {

    private static final String GENERATED_PACKAGE = "com.metaml.generated.delegate";

    // one class per unique delegateExpression, not per task - two activities pointing at the same
    // delegateExpression share the one Spring bean already, generating it twice would just be two
    // classes fighting over the same @Component name
    public List<GeneratedDelegate> generate(String bpmnXml) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        Map<String, GeneratedDelegate> byBeanName = new LinkedHashMap<>();
        for (ServiceTask task : model.getModelElementsByType(ServiceTask.class)) {
            String raw = task.getCamundaDelegateExpression();
            String beanName = unwrap(raw);
            if (beanName.isBlank()) {
                continue;
            }
            byBeanName.computeIfAbsent(beanName, bn -> {
                String className = toClassName(bn);
                String source = renderSource(className, bn, task.getName());
                return new GeneratedDelegate(bn, className, task.getName(), source);
            });
        }
        return new ArrayList<>(byBeanName.values());
    }

    // delegateExpression is stored (and read back) as the literal attribute text, "${beanName}" -
    // this is the one place that ${...} wrapper gets stripped down to the bean name underneath it
    private static String unwrap(String delegateExpression) {
        if (delegateExpression == null) {
            return "";
        }
        String trimmed = delegateExpression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    // bean names in a delegateExpression are conventionally already valid Java identifiers
    // (camelCase, e.g. calculateInterestService), but this is user-authored BPMN, not generated
    // output - a stray character shouldn't produce a .java file that fails to compile
    private static String toClassName(String beanName) {
        StringBuilder sanitized = new StringBuilder(beanName.length());
        for (int i = 0; i < beanName.length(); i++) {
            char c = beanName.charAt(i);
            boolean valid = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sanitized.append(valid ? c : '_');
        }
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.insert(0, '_');
        }
        sanitized.setCharAt(0, Character.toUpperCase(sanitized.charAt(0)));
        return sanitized.toString();
    }

    private static String renderSource(String className, String beanName, String taskName) {
        String label = (taskName == null || taskName.isBlank()) ? "(unnamed activity)" : taskName;
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.springframework.stereotype.Component;

                // Generated from the BPMN activity "%s" (camunda:delegateExpression="${%s}").
                // Fill in the actual logic below - this stub only exists so the process can deploy
                // and run end to end without a NoClassDefFoundError on this bean.
                @Component("%s")
                public class %s implements JavaDelegate {

                    @Override
                    public void execute(DelegateExecution execution) {
                        // TODO: implement %s
                    }
                }
                """.formatted(GENERATED_PACKAGE, label, beanName, beanName, className, label);
    }
}
