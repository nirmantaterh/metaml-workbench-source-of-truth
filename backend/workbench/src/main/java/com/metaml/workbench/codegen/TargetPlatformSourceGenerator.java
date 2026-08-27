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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// Generates the simple Spring beans expected by the fixed com.tp.TargetPlatform template. The BPMN is normalised to reference each generated bean by the BPMN element id, so both camunda:delegateExpression and camunda:class inputs work consistently in the target platform. Lockstep synchronization (added for delegate-expression BPMNs): The older camundademo pipeline's BPMNs already have signal catch events between activities on BOTH sides (proxy and twin), so SignalBroadcaster's REQUEST/RESPONSE protocol naturally pairs them. Delegate-expression BPMNs have none: the proxy runs straight through all serviceTasks with no wait state, and the twin's receiveTask+message pattern creates message subscriptions that SignalBroadcaster (which queries eventType="signal") never sees. To close this gap this generator now: 1. Proxy BPMN: inserts a signal intermediateCatchEvent after each serviceTask (wait state) 2. Twin BPMN: replaces each receiveTask with a signal intermediateCatchEvent using the SAME signal name, preserving the implicit parallel split to the _automate serviceTask Both sides then wait on sync_<activityId> signals that SignalBroadcaster delivers via the same proven bidirectional handoff: twin first (REQUEST), then proxy (RESPONSE) once twin has advanced - identical to how the old signal-gated BPMNs work. Load-bearing assumption this relies on: SignalBroadcaster only ever concludes "twin has moved past this signal" (and so only then delivers RESPONSE) from state it reads AFTER the twin's signalEventReceived() call has returned - see that generated class's own responderHasAdvancedPast comment. That is only a valid proof of completion because the twin's _automate delegate's execute() runs synchronously, in the SAME Camunda command/transaction as the signal delivery that triggers it: nothing about the flow becomes visible to a separate query until the whole transaction, delegate included, commits. A twin automation delegate that hands work off to another thread and returns early (instead of blocking until that work is done) would silently break the invariant this generator exists to establish.
@Component
public class TargetPlatformSourceGenerator {
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    static final String SYNC_SIGNAL_PREFIX = "sync_";

    public record GeneratedSource(String relativeDirectory, String className, String source) { }
    public record Result(String bpmnXml, List<GeneratedSource> sources,
                         Set<String> syncSignalNames, Set<String> syncActivityIds) { }

    public Result generate(String bpmnXml, boolean twin) {
        return generate(bpmnXml, twin, null);
    }

    public Result generate(String bpmnXml, boolean twin, Set<String> syncActivityIdsFromProxy) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            List<GeneratedSource> sources = new ArrayList<>();
            List<String> proxyServiceTaskIds = new ArrayList<>();
            NodeList all = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                String localName = element.getLocalName();
                if (localName == null) continue;
                boolean activity = localName != null && localName.endsWith("Task");
                boolean event = localName.endsWith("Event");
                String expression = element.getAttributeNS(CAMUNDA_NS, "delegateExpression");
                String javaClass = element.getAttributeNS(CAMUNDA_NS, "class");
                if ((!activity && !event) || (expression.isBlank() && javaClass.isBlank())) continue;
                String id = element.getAttribute("id");
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Delegated BPMN element has no id");
                // The twin side always gets its own bean name, distinct from whatever id the proxy BPMN uses - an authored twin (see saveModelWithAuthoredTwin) is free to reuse the exact same activity/event id as its proxy (a hand-mirrored twin naturally would), and Spring's @Component("...") registers by that literal string regardless of the proxy/twin package split below, so two different classes claiming the same bean name make the generated app fail to start with ConflictingBeanDefinitionException. Same fix already applied to executionListener beans below; className is left unqualified since Java tolerates identical simple class names across packages - only the Spring bean name actually collides.
                String beanName = twin ? camel(id) + "Twin" : camel(id);
                String className = pascal(id);
                // A fixed template needs a deterministic, component-scanned bean name. Normalising also makes a camunda:class task usable without requiring an arbitrary FQCN.
                element.removeAttributeNS(CAMUNDA_NS, "class");
                element.setAttributeNS(CAMUNDA_NS, "camunda:delegateExpression", "${" + beanName + "}");
                String side = twin ? "TWIN" : "PROXY";
                String label = event ? side + " (MSG)" : side;
                String directory = (twin ? "twin" : "proxy") + "/" + (event ? "events" : "delegates");
                String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
                sources.add(new GeneratedSource(directory, className, render(packageName, className, beanName, label)));

                // Track proxy service task IDs for lockstep sync (not events, not twin _automate tasks)
                if (activity && !twin) {
                    proxyServiceTaskIds.add(id);
                }
            }
            sources.addAll(scanExecutionListeners(document, twin));
            sources.addAll(scanTaskListeners(document, twin));

            // Apply bidirectional lockstep synchronization: insert signal catch events so both proxy and twin wait at the same signals after each activity. SignalBroadcaster's existing REQUEST/RESPONSE protocol handles the rest (see its own comment).
            Set<String> syncSignalNames = new LinkedHashSet<>();
            Set<String> syncActivityIds = new LinkedHashSet<>();
            if (!twin && !proxyServiceTaskIds.isEmpty()) {
                syncSignalNames = insertProxySyncSignals(document, proxyServiceTaskIds);
                // Only report IDs where signals were actually created (requires outgoing flows)
                for (String sn : syncSignalNames) {
                    syncActivityIds.add(sn.substring(SYNC_SIGNAL_PREFIX.length()));
                }
            } else if (twin && syncActivityIdsFromProxy != null && !syncActivityIdsFromProxy.isEmpty()) {
                syncSignalNames = replaceTwinReceiveTasksWithSignals(document, syncActivityIdsFromProxy);
                syncActivityIds.addAll(syncActivityIdsFromProxy);
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(xml));
            return new Result(xml.toString(), sources, syncSignalNames, syncActivityIds);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not scan BPMN for TargetPlatform delegates: " + e.getMessage(), e);
        }
    }

    // ── Lockstep synchronization: Proxy BPMN ─────────────────────────────────── For each proxy serviceTask, inserts an intermediateCatchEvent with a signalEventDefinition directly after it. The signal name (sync_<activityId>) will match the same signal inserted into the twin BPMN, giving SignalBroadcaster's REQUEST/RESPONSE rendezvous two subscription points to pair - same architecture the older camundademo pipeline's BPMN signal gates use.
    private Set<String> insertProxySyncSignals(Document document, List<String> activityIds) {
        Set<String> signalNames = new LinkedHashSet<>();
        Element definitions = document.getDocumentElement();
        String bpmnNs = definitions.getNamespaceURI();
        String prefix = definitions.getPrefix();

        NodeList processes = document.getElementsByTagNameNS(bpmnNs, "process");
        if (processes.getLength() == 0) return signalNames;
        Element process = (Element) processes.item(0);

        for (String activityId : activityIds) {
            String signalName = SYNC_SIGNAL_PREFIX + activityId;
            ensureSignalNameAvailable(document, bpmnNs, signalName, activityId);
            String catchEventId = uniqueId(document, "sync_evt_" + activityId);
            String newFlowId = uniqueId(document, "sync_flow_" + activityId);
            String signalId = uniqueId(document, "Signal_sync_" + activityId);
            String signalEventDefId = uniqueId(document, "SED_sync_" + activityId);

            signalNames.add(signalName);

            Element serviceTask = findElementById(document, activityId);
            if (serviceTask == null) continue;

            // Find all outgoing sequence flows from this serviceTask
            List<Element> outgoingFlows = findFlowsBySourceRef(document, bpmnNs, activityId);
            if (outgoingFlows.isEmpty()) continue;

            // Create the intermediate catch event. BPMN 2.0 XSD element order: incoming/outgoing (from tFlowNode) BEFORE signalEventDefinition (from tCatchEvent).
            Element catchEvent = document.createElementNS(bpmnNs, qname(prefix, "intermediateCatchEvent"));
            catchEvent.setAttribute("id", catchEventId);

            // Add incoming reference (the new bridge flow from serviceTask) - must be first
            Element incomingElem = document.createElementNS(bpmnNs, qname(prefix, "incoming"));
            incomingElem.setTextContent(newFlowId);
            catchEvent.appendChild(incomingElem);

            // Redirect each outgoing flow: change sourceRef from serviceTask to catch event
            for (Element flow : outgoingFlows) {
                flow.setAttribute("sourceRef", catchEventId);
                Element outgoingElem = document.createElementNS(bpmnNs, qname(prefix, "outgoing"));
                outgoingElem.setTextContent(flow.getAttribute("id"));
                catchEvent.appendChild(outgoingElem);
            }

            // Signal event definition comes AFTER incoming/outgoing per BPMN XSD
            Element signalEventDef = document.createElementNS(bpmnNs, qname(prefix, "signalEventDefinition"));
            signalEventDef.setAttribute("id", signalEventDefId);
            signalEventDef.setAttribute("signalRef", signalId);
            catchEvent.appendChild(signalEventDef);

            // Update the serviceTask's <outgoing> children to point to the new bridge flow
            removeChildElementsByLocalName(serviceTask, "outgoing");
            Element newOutgoing = document.createElementNS(bpmnNs, qname(prefix, "outgoing"));
            newOutgoing.setTextContent(newFlowId);
            serviceTask.appendChild(newOutgoing);

            // Create the bridge sequence flow: serviceTask → catch event
            Element bridgeFlow = document.createElementNS(bpmnNs, qname(prefix, "sequenceFlow"));
            bridgeFlow.setAttribute("id", newFlowId);
            bridgeFlow.setAttribute("sourceRef", activityId);
            bridgeFlow.setAttribute("targetRef", catchEventId);

            process.appendChild(catchEvent);
            process.appendChild(bridgeFlow);

            // Signal declaration on the definitions element - must appear BEFORE BPMNDiagram per XSD
            Element signal = document.createElementNS(bpmnNs, qname(prefix, "signal"));
            signal.setAttribute("id", signalId);
            signal.setAttribute("name", signalName);
            insertBeforeDiagram(definitions, signal);
        }
        return signalNames;
    }

    // ── Lockstep synchronization: Twin BPMN ──────────────────────────────────── Replaces the TwinModelGenerator's receiveTask elements (which create message subscriptions the existing SignalBroadcaster cannot poll) with signal intermediateCatchEvents using the same sync_<activityId> signal names the proxy side waits on. The implicit parallel split from the receiveTask's two outgoing flows is preserved: one branch fires the _automate delegate, the other advances to the next signal gate.
    private Set<String> replaceTwinReceiveTasksWithSignals(Document document, Set<String> activityIds) {
        Set<String> signalNames = new LinkedHashSet<>();
        Element definitions = document.getDocumentElement();
        String bpmnNs = definitions.getNamespaceURI();
        String prefix = definitions.getPrefix();

        // Collect matching receiveTask elements (can't modify DOM while iterating getElementsBy*)
        List<Element> receiveTasks = new ArrayList<>();
        NodeList rtNodes = document.getElementsByTagNameNS(bpmnNs, "receiveTask");
        for (int i = 0; i < rtNodes.getLength(); i++) {
            Element rt = (Element) rtNodes.item(i);
            if (activityIds.contains(rt.getAttribute("id"))) {
                receiveTasks.add(rt);
            }
        }

        for (Element receiveTask : receiveTasks) {
            String activityId = receiveTask.getAttribute("id");
            String signalName = SYNC_SIGNAL_PREFIX + activityId;
            ensureSignalNameAvailable(document, bpmnNs, signalName, activityId);
            String signalId = uniqueId(document, "Signal_sync_" + activityId);
            String signalEventDefId = uniqueId(document, "SED_sync_" + activityId);

            signalNames.add(signalName);

            // Create replacement intermediateCatchEvent keeping the same id so all existing sequence flow sourceRef/targetRef references stay valid. BPMN 2.0 XSD element order: incoming/outgoing BEFORE signalEventDefinition.
            Element catchEvent = document.createElementNS(bpmnNs, qname(prefix, "intermediateCatchEvent"));
            catchEvent.setAttribute("id", activityId);
            if (receiveTask.hasAttribute("name")) {
                catchEvent.setAttribute("name", receiveTask.getAttribute("name"));
            }

            // Carry over incoming/outgoing children from the receiveTask FIRST (XSD order)
            NodeList children = receiveTask.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element el) {
                    String ln = el.getLocalName();
                    if ("incoming".equals(ln) || "outgoing".equals(ln)) {
                        catchEvent.appendChild(child.cloneNode(true));
                    }
                }
            }

            // Signal event definition comes AFTER incoming/outgoing per BPMN XSD
            Element signalEventDef = document.createElementNS(bpmnNs, qname(prefix, "signalEventDefinition"));
            signalEventDef.setAttribute("id", signalEventDefId);
            signalEventDef.setAttribute("signalRef", signalId);
            catchEvent.appendChild(signalEventDef);

            receiveTask.getParentNode().replaceChild(catchEvent, receiveTask);

            // Signal declaration - must appear BEFORE BPMNDiagram per XSD
            Element signal = document.createElementNS(bpmnNs, qname(prefix, "signal"));
            signal.setAttribute("id", signalId);
            signal.setAttribute("name", signalName);
            insertBeforeDiagram(definitions, signal);
        }

        // Remove TwinAdvance_ message declarations for the replaced activities
        removeTwinAdvanceMessages(document, bpmnNs, activityIds);
        return signalNames;
    }

    private void removeTwinAdvanceMessages(Document document, String bpmnNs, Set<String> activityIds) {
        NodeList messages = document.getElementsByTagNameNS(bpmnNs, "message");
        List<Element> toRemove = new ArrayList<>();
        for (int i = 0; i < messages.getLength(); i++) {
            Element msg = (Element) messages.item(i);
            String name = msg.getAttribute("name");
            if (name != null && name.startsWith("TwinAdvance_")) {
                String actId = name.substring("TwinAdvance_".length());
                if (activityIds.contains(actId)) {
                    toRemove.add(msg);
                }
            }
        }
        for (Element msg : toRemove) {
            msg.getParentNode().removeChild(msg);
        }
    }

    // ── DOM helpers ────────────────────────────────────────────────────────────

    // Inserts a child element into definitions BEFORE the first BPMNDiagram element (or any DI namespace element). BPMN 2.0 XSD requires rootElements (signal, message, process, etc.) before BPMNDiagram elements. Falls back to appendChild if no diagram is found.
    private static void insertBeforeDiagram(Element definitions, Element child) {
        NodeList children = definitions.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && "BPMNDiagram".equals(el.getLocalName())) {
                definitions.insertBefore(child, el);
                return;
            }
        }
        definitions.appendChild(child);
    }

    private static String qname(String prefix, String localName) {
        return prefix != null ? prefix + ":" + localName : localName;
    }

    // Returns candidate unchanged if no element in this document already uses it as an "id", otherwise probes candidate_2, candidate_3, ... deterministically (never random - the same source BPMN must generate the same output every time) until a free one is found. Guards against two cases: an activity id that happens to end in something like "_evt" or "_flow" already, and re-running generation on a proxy/twin BPMN that has already been through this transformation once (its own previously-inserted sync_evt_*/Signal_sync_* ids are already sitting in the document). Local to whichever document is passed in - the proxy and twin catch-event/flow/signal-element ids are never referenced from the other side's BPMN, so resolving a clash here needs no coordination across the two generate() calls.
    private static String uniqueId(Document document, String candidate) {
        if (findElementById(document, candidate) == null) {
            return candidate;
        }
        for (int suffix = 2; ; suffix++) {
            String probe = candidate + "_" + suffix;
            if (findElementById(document, probe) == null) {
                return probe;
            }
        }
    }

    // Unlike element ids (see uniqueId), the SIGNAL NAME itself is not free to rename on collision: SignalBroadcaster pairs proxy and twin purely by matching signal name, and the twin side is handed this exact name via Result.syncActivityIds() with no channel back to the proxy side to report "actually, use a different name instead". Silently substituting one here would need that channel to exist so both sides still agree - out of scope for this generator without widening Result beyond a plain activity-id set. Failing loudly and telling the caller how to fix their model (rename the clashing signal or activity) is the safe alternative to guessing.
    private static void ensureSignalNameAvailable(Document document, String bpmnNs, String signalName,
            String activityId) {
        NodeList signals = document.getElementsByTagNameNS(bpmnNs, "signal");
        for (int i = 0; i < signals.getLength(); i++) {
            Element existing = (Element) signals.item(i);
            if (signalName.equals(existing.getAttribute("name"))) {
                throw new IllegalStateException("Cannot insert a lockstep sync signal named '" + signalName
                        + "' for activity '" + activityId + "': a signal with that exact name already exists "
                        + "in this BPMN (id=" + existing.getAttribute("id") + "). Rename the pre-existing "
                        + "signal or the activity to resolve the clash before regenerating.");
            }
        }
    }

    private static Element findElementById(Document document, String id) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el && id.equals(el.getAttribute("id"))) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> findFlowsBySourceRef(Document document, String bpmnNs, String sourceRef) {
        NodeList flows = document.getElementsByTagNameNS(bpmnNs, "sequenceFlow");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < flows.getLength(); i++) {
            Element flow = (Element) flows.item(i);
            if (sourceRef.equals(flow.getAttribute("sourceRef"))) {
                result.add(flow);
            }
        }
        return result;
    }

    private static void removeChildElementsByLocalName(Element parent, String childLocalName) {
        List<Node> toRemove = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && childLocalName.equals(el.getLocalName())) {
                toRemove.add(child);
            }
        }
        for (Node n : toRemove) {
            parent.removeChild(n);
        }
    }

    // ── Existing helpers (unchanged) ───────────────────────────────────────────

    // camunda:executionListener is not scanned by the activity/event loop above (it is a CHILD element under extensionElements, not an attribute directly on the task/event it applies to) but references a delegateExpression bean the same way - one that must exist in the generated project or the engine throws PropertyNotFoundException the moment the listener's own event (start/end/take) fires. RedCollar's real BPMNs lean on exactly this: every Manuf activity's "end" listener notifies the twin side (${manufTaskCompletionListener}), a bean that belongs to the OLDER camundademo-based Target Harness Platform, not this fixed template - without a stub here it deploys fine and then dies with an unrecoverable incident the first time any activity actually completes. Deduplicated by bean name (the same listener is typically wired onto MANY activities) rather than by BPMN element id the way activities/events are - a listener has no element of its own to derive an id from, and the whole point is one class per DISTINCT bean the BPMN names, not one per place it's referenced from.
    private List<GeneratedSource> scanExecutionListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "executionListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            // The twin side always gets its own bean name, distinct from whatever the proxy BPMN named it - proxy and twin can genuinely name the same original listener (a structural mirror of the proxy always does; see TargetPlatformTwinMirrorGenerator, and two independently authored BPMNs could too by coincidence), and Spring refuses to start with two different classes registered under one bean name. The BPMN itself gets rewritten to match, the same way activity/event delegateExpressions above do.
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

    // camunda:taskListener lives inside a userTask's <extensionElements>, the same shape executionListener does, but it is a genuinely different Camunda API: it fires on the user task's own lifecycle events (create/assignment/complete/delete/timeout/update, read from DelegateTask.getEventName() at runtime) rather than on activity execution, and Camunda invokes it through org.camunda.bpm.engine.delegate.TaskListener.notify(DelegateTask), not ExecutionListener.notify(DelegateExecution) - the two are not interchangeable, so a TaskListener stub must implement the right interface or the engine throws a ClassCastException the first time the task's listener event actually fires. Mirrors scanExecutionListeners in every other respect: same twin bean-suffixing to avoid a Spring ConflictingBeanDefinitionException when proxy and twin name the same original bean (an authored or mirrored twin can both do this - see that method's own comment), same dedup-by-bean-name (one class per distinct bean, however many tasks/events reference it), same {proxy|twin}/listeners/ output location - a TaskListener and an ExecutionListener are both "a bean Camunda calls back into", just through different interfaces, so they share a home rather than inventing a parallel directory for what is architecturally the same idea. Only the delegateExpression form is generated. camunda:class would need FQCN-based generation (see DelegateClassGenerator.generateFromJavaClass for that different shape, which this fixed-package template cannot reuse directly since a camunda:class listener must land at the exact package the BPMN names), and a raw UEL "expression" attribute names something arbitrary this generator has no safe way to turn into a class. Both are left unrewritten rather than silently mis-generated.
    private List<GeneratedSource> scanTaskListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "taskListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            String beanName = twin ? originalBeanName + "Twin" : originalBeanName;
            listener.setAttribute("delegateExpression", "${" + beanName + "}");
            if (!seen.add(beanName)) continue;
            String className = pascal(beanName);
            String directory = (twin ? "twin" : "proxy") + "/listeners";
            String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
            String label = (twin ? "TWIN" : "PROXY") + " (TASK LISTENER)";
            sources.add(new GeneratedSource(directory, className,
                    renderTaskListener(packageName, className, beanName, label)));
        }
        return sources;
    }

    private static String renderTaskListener(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateTask;
                import org.camunda.bpm.engine.delegate.TaskListener;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements TaskListener {
                    @Override
                    public void notify(DelegateTask delegateTask) {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
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
