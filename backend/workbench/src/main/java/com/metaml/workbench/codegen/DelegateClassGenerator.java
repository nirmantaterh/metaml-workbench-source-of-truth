package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaTaskListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// New scope item 3 (BPMN Processing): parse a saved model and generate a real Java class per
// delegateExpression, replacing hand-written ones. The class name comes from the
// delegateExpression itself (e.g. "${calculateInterestService}" -> class
// CalculateInterestService), not the task's display name - the expression is what Camunda
// actually looks up at runtime, so deriving from it is the only choice guaranteed to produce a
// class the generated app can run. Deriving from the display name risks exactly the mismatch that
// kicked this off ("Calculate Interest" next to delegateExpression "calculateInterestService" -
// different casing, different wording).
//
// Two BPMN shapes carry a delegateExpression, and they're not interchangeable - see DelegateKind's
// own comment. Originally this only scanned service tasks (Joanna's own example is one); this
// repo's own demo models use a userTask taskListener instead, so it silently produced zero
// delegates and shipped a project that crashed on the first completed task. Found by trying it.
@Component
public class DelegateClassGenerator {

    // used when a caller doesn't care where the class ultimately lives (the standalone
    // preview/inspection path) - a real generated project overrides this via the packageName
    // parameter below so the class lands somewhere Spring's component scan will actually find it
    static final String DEFAULT_PACKAGE = "com.metaml.generated.delegate";

    public List<GeneratedDelegate> generate(String bpmnXml) {
        return generate(bpmnXml, DEFAULT_PACKAGE);
    }

    // one class per unique delegateExpression, not per task - two activities pointing at the same
    // delegateExpression share the one Spring bean already, generating it twice would just be two
    // classes fighting over the same @Component name.
    //
    // Deduped by className rather than beanName: two different bean names can sanitize to the same
    // Java identifier (toClassName maps every illegal character to '_', so "bad-name" and
    // "bad_name" both become "Bad_name"). Deduping on the raw bean name let both through as
    // separate GeneratedDelegates fighting over the same file - whichever wrote second would
    // silently win, and the other's bean would never exist at runtime.
    //
    // packageName must match wherever the caller actually places the .java file - javac compiles a
    // file whose package disagrees with its directory, but Spring Boot's default @ComponentScan
    // only looks under the application class's own package, so a mismatch here means the bean
    // silently never registers even though the build succeeds. SpringBootProjectGenerator passes
    // the template's real delegate package for this reason.
    public List<GeneratedDelegate> generate(String bpmnXml, String packageName) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        // Phase 3B: collect every BPMN element that resolves to each className first, rather than
        // building the GeneratedDelegate directly - a className can legitimately be reached by more
        // than one BPMN element (see the shared-delegate test below), and this is what lets the loop
        // afterward tell "reached by exactly one element, safe to record" apart from "shared, would
        // have to guess which one" without changing any of the dedup-by-className behavior itself
        Map<String, List<Source>> sourcesByClassName = new LinkedHashMap<>();

        for (ServiceTask task : model.getModelElementsByType(ServiceTask.class)) {
            addSource(sourcesByClassName, task.getId(), task.getCamundaDelegateExpression(), task.getName(),
                    DelegateKind.SERVICE_TASK);
        }

        // task listeners live in a user task's <extensionElements>, not as an attribute on the task
        // itself - camunda-bpm-model only exposes them through a typed query on that element, there
        // is no getCamundaTaskListeners() shortcut on UserTask the way there is for a service task's
        // own delegateExpression
        for (UserTask task : model.getModelElementsByType(UserTask.class)) {
            ExtensionElements extensionElements = task.getExtensionElements();
            if (extensionElements == null) {
                continue;
            }
            for (CamundaTaskListener listener : extensionElements.getElementsQuery()
                    .filterByType(CamundaTaskListener.class).list()) {
                addSource(sourcesByClassName, task.getId(), listener.getCamundaDelegateExpression(), task.getName(),
                        DelegateKind.TASK_LISTENER);
            }
        }

        List<GeneratedDelegate> delegates = new ArrayList<>();
        for (Map.Entry<String, List<Source>> entry : sourcesByClassName.entrySet()) {
            String className = entry.getKey();
            List<Source> sources = entry.getValue();
            // the first BPMN element encountered still names/comments the generated class, exactly
            // as before this change (see renderSource below) - only bpmnElementId is new
            Source first = sources.get(0);
            // Two elements reaching this className for the SAME bean name is fine - that's one
            // shared Spring bean, which is exactly what Camunda does at runtime. Two elements
            // reaching it via DIFFERENT bean names is not: only one class can be written to that
            // path, so the other element's bean silently never exists and the process blows up the
            // first time a token reaches that task. Dedup used to just drop the loser here, which
            // is why the failure only ever surfaced at runtime. Camunda accepts both expressions at
            // deploy time, so nothing upstream catches it either - this is the point where it can
            // still be attributed to a specific element.
            Source loser = firstWithDifferentBeanName(sources, first.beanName());
            if (loser != null) {
                throw InvalidDelegateExpressionException.collision(loser.elementId(), loser.taskName(),
                        "${" + loser.beanName() + "}", "${" + first.beanName() + "}", className);
            }
            // more than one BPMN element sharing this bean is a real, legitimate case (two
            // activities pointing at the same service) - "go to error" pointing at an arbitrary one
            // of them would be worse than not pointing anywhere, so this is left null rather than
            // fabricated
            String bpmnElementId = sources.size() == 1 ? first.elementId() : null;
            String source = renderSource(packageName, className, first.beanName(), first.taskName(), first.kind());
            delegates.add(new GeneratedDelegate(first.beanName(), className, first.taskName(), first.kind(), source,
                    bpmnElementId));
        }
        return delegates;
    }

    // the element that loses the collision - the first one whose bean name isn't the one the
    // generated class is going to be named after. Returns null when every element here agrees on
    // the bean name, i.e. the legitimate shared-delegate case.
    private static Source firstWithDifferentBeanName(List<Source> sources, String beanName) {
        for (Source source : sources) {
            if (!source.beanName().equals(beanName)) {
                return source;
            }
        }
        return null;
    }

    // the BPMN element that pointed at this delegateExpression, kept alongside the same
    // beanName/taskName/kind DelegateClassGenerator always needed - elementId is new (Phase 3B),
    // everything else here already existed as addIfPresent's own local variables
    private record Source(String elementId, String beanName, String taskName, DelegateKind kind) {
    }

    private static void addSource(Map<String, List<Source>> sourcesByClassName, String elementId,
            String rawExpression, String taskName, DelegateKind kind) {
        // Absent and present-but-unusable are NOT the same: absent means nothing was ever claimed,
        // skip it (Camunda rejects such a model at save anyway). Present but empty/unresolvable
        // means the BPMN claims this task runs a delegate and names none - fail now, naming the
        // element responsible, rather than shipping a project that fails at runtime instead.
        if (rawExpression == null) {
            return;
        }
        String beanName = unwrap(rawExpression);
        if (beanName.isBlank()) {
            throw new InvalidDelegateExpressionException(elementId, taskName, rawExpression);
        }
        String className = toClassName(beanName);
        sourcesByClassName.computeIfAbsent(className, cn -> new ArrayList<>())
                .add(new Source(elementId, beanName, taskName, kind));
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

    // task labels are free-text from the modeler, not code - BPMN can carry an embedded newline in
    // a name attribute (Joanna's own loanApproval.bpmn does: "Calculate\nInterest"), and every use
    // of this label lands inside a single-line // comment, where an unsanitized newline breaks the
    // generated file's syntax.
    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    private static String renderSource(String packageName, String className, String beanName, String taskName,
            DelegateKind kind) {
        String label = (taskName == null || taskName.isBlank()) ? "(unnamed activity)" : sanitizeForComment(taskName);
        return kind == DelegateKind.SERVICE_TASK
                ? renderServiceTaskSource(packageName, className, beanName, label)
                : renderTaskListenerSource(packageName, className, beanName, label);
    }

    private static String renderServiceTaskSource(String packageName, String className, String beanName,
            String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                // Generated from the BPMN activity "%s" (camunda:delegateExpression="${%s}").
                // Logs on execution so the process can deploy and run end to end and its execution
                // is directly observable - replace the log line below with real logic when ready.
                @Component("%s")
                public class %s implements JavaDelegate {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void execute(DelegateExecution execution) {
                        logger.info("Executing generated delegate for activity \\"%s\\" "
                                + "(process instance {})", execution.getProcessInstanceId());
                    }
                }
                """.formatted(packageName, label, beanName, beanName, className, className, label);
    }

    // A taskListener's delegateExpression points at a TaskListener, not a JavaDelegate - it fires
    // on the user task's lifecycle event (create/assign/complete/...), it doesn't run instead of
    // the human task the way a service task's own delegateExpression does. Generating a JavaDelegate
    // stub for one of these would compile and even deploy, since Camunda only checks the interface
    // when it actually tries to invoke the listener - so the failure would show up as a
    // ClassCastException the first time someone touched that task, not at generation time.
    private static String renderTaskListenerSource(String packageName, String className, String beanName,
            String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateTask;
                import org.camunda.bpm.engine.delegate.TaskListener;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                // Generated from the BPMN user task "%s" (camunda:taskListener delegateExpression="${%s}").
                // Logs on execution so the process can deploy and run end to end and its execution
                // is directly observable - replace the log line below with real logic when ready.
                @Component("%s")
                public class %s implements TaskListener {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void notify(DelegateTask delegateTask) {
                        logger.info("Executing generated task listener for activity \\"%s\\" "
                                + "(process instance {})", delegateTask.getProcessInstanceId());
                    }
                }
                """.formatted(packageName, label, beanName, beanName, className, className, label);
    }
}
