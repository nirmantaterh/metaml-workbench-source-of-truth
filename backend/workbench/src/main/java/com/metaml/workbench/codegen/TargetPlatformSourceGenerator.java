package com.metaml.workbench.codegen;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

// Generates the simple Spring beans expected by the fixed com.tp.TargetPlatform template.
// The BPMN is normalised to reference each generated bean by the BPMN element id, so both
// camunda:delegateExpression and camunda:class inputs work consistently in the target platform.
@Component
public class TargetPlatformSourceGenerator {
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

    public record GeneratedSource(String relativeDirectory, String className, String source) { }
    public record Result(String bpmnXml, List<GeneratedSource> sources) { }

    public Result generate(String bpmnXml, boolean twin) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            List<GeneratedSource> sources = new ArrayList<>();
            NodeList all = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                String localName = element.getLocalName();
                if (localName == null) continue;
                boolean activity = "serviceTask".equals(localName);
                boolean event = localName.endsWith("Event");
                String expression = element.getAttributeNS(CAMUNDA_NS, "delegateExpression");
                String javaClass = element.getAttributeNS(CAMUNDA_NS, "class");
                if ((!activity && !event) || (expression.isBlank() && javaClass.isBlank())) continue;
                String id = element.getAttribute("id");
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Delegated BPMN element has no id");
                String beanName = camel(id);
                String className = pascal(id);
                // A fixed template needs a deterministic, component-scanned bean name. Normalising
                // also makes a camunda:class task usable without requiring an arbitrary FQCN.
                element.removeAttributeNS(CAMUNDA_NS, "class");
                element.setAttributeNS(CAMUNDA_NS, "camunda:delegateExpression", "${" + beanName + "}");
                String side = twin ? "TWIN" : "PROXY";
                String label = event ? side + " (MSG)" : side;
                String directory = (twin ? "twin" : "proxy") + "/" + (event ? "events" : "delegates");
                String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
                sources.add(new GeneratedSource(directory, className, render(packageName, className, beanName, label)));
            }
            sources.addAll(scanExecutionListeners(document, twin));
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(xml));
            return new Result(xml.toString(), sources);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not scan BPMN for TargetPlatform delegates: " + e.getMessage(), e);
        }
    }

    // camunda:executionListener is not scanned by the activity/event loop above (it is a CHILD
    // element under extensionElements, not an attribute directly on the task/event it applies to)
    // but references a delegateExpression bean the same way - one that must exist in the generated
    // project or the engine throws PropertyNotFoundException the moment the listener's own event
    // (start/end/take) fires. RedCollar's real BPMNs lean on exactly this: every Manuf activity's
    // "end" listener notifies the twin side (${manufTaskCompletionListener}), a bean that belongs
    // to the OLDER camundademo-based Target Harness Platform, not this fixed template - without a
    // stub here it deploys fine and then dies with an unrecoverable incident the first time any
    // activity actually completes.
    //
    // Deduplicated by bean name (the same listener is typically wired onto MANY activities) rather
    // than by BPMN element id the way activities/events are - a listener has no element of its own
    // to derive an id from, and the whole point is one class per DISTINCT bean the BPMN names, not
    // one per place it's referenced from.
    private List<GeneratedSource> scanExecutionListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "executionListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            // The twin side always gets its own bean name, distinct from whatever the proxy BPMN
            // named it - proxy and twin can genuinely name the same original listener (a
            // structural mirror of the proxy always does; see TargetPlatformTwinMirrorGenerator,
            // and two independently authored BPMNs could too by coincidence), and Spring refuses
            // to start with two different classes registered under one bean name. The BPMN itself
            // gets rewritten to match, the same way activity/event delegateExpressions above do.
            String beanName = twin ? originalBeanName + "Twin" : originalBeanName;
            listener.setAttribute("delegateExpression", "${" + beanName + "}");
            if (!seen.add(beanName)) continue;
            String className = pascal(beanName);
            String directory = (twin ? "twin" : "proxy") + "/listeners";
            String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
            String label = (twin ? "TWIN" : "PROXY") + " (LISTENER)";
            sources.add(new GeneratedSource(directory, className, renderListener(packageName, className, beanName, label)));
        }
        return sources;
    }

    private static String stripExpression(String expression) {
        if (expression == null) return "";
        String trimmed = expression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static String renderListener(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.ExecutionListener;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements ExecutionListener {
                    @Override
                    public void notify(DelegateExecution execution) throws Exception {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
    }

    private static String render(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements JavaDelegate {
                    @Override
                    public void execute(DelegateExecution arg0) throws Exception {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
    }

    private static String pascal(String id) {
        StringBuilder out = new StringBuilder();
        boolean uppercase = true;
        for (char c : id.toCharArray()) {
            if (!Character.isJavaIdentifierPart(c)) { uppercase = true; continue; }
            out.append(uppercase ? Character.toUpperCase(c) : c);
            uppercase = false;
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, 'X');
        return out.toString();
    }

    private static String camel(String id) {
        String value = pascal(id);
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
