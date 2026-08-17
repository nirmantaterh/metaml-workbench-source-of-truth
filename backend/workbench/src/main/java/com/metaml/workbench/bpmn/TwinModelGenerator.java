package com.metaml.workbench.bpmn;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.LoopCardinality;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.xml.instance.DomDocument;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Keeps twin advancement synchronized by pairing each user task with a receive and service task.
@Component
public class TwinModelGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TwinModelGenerator.class);

    private static final String METAML_NAMESPACE = "http://metaml.com/schema/bpmn/metaml";
    private static final String METAML_PREFIX = "metaml";
    private static final String EXTENSION_ELEMENTS_NAME = "extensionElements";
    private static final String ID_ATTRIBUTE = "id";

    private static final String TWIN_PROCESS_ID_SUFFIX = "_twin";
    private static final String TWIN_PROCESS_NAME_SUFFIX = " (twin)";
    private static final String TWIN_DELEGATE_EXPRESSION = "${twinAutomationDelegate}";
    private static final String TWIN_MESSAGE_PREFIX = "TwinAdvance_";

    private static final String AUTOMATION_TASK_ID_SUFFIX = "_automate";
    private static final String WRAPPER_ID_SUFFIX = "_sync";
    private static final String WRAPPER_START_ID_SUFFIX = "_sync_start";
    private static final String WRAPPER_END_ID_SUFFIX = "_sync_end";
    private static final String FLOW_TO_AUTOMATION_SUFFIX = "_to_automate";
    private static final String WRAPPER_START_FLOW_SUFFIX = "_sync_start_flow";
    private static final String WRAPPER_END_FLOW_SUFFIX = "_sync_end_flow";

    private static final String DEFINITIONS_ID_PREFIX = "Definitions_";
    private static final String CONDITION_EXPRESSION_ID_PREFIX = "ConditionExpression_";
    private static final String MESSAGE_ID_PREFIX = "Message_";
    private static final String MI_LOOP_CHARACTERISTICS_ID_PREFIX = "MultiInstance_";
    private static final String MI_CARDINALITY_ID_PREFIX = "LoopCardinality_";

    // original activity ids ending in any of these would collide with their own derived twin ids
    private static final List<String> RESERVED_ID_SUFFIXES = List.of(AUTOMATION_TASK_ID_SUFFIX,
            WRAPPER_ID_SUFFIX, WRAPPER_START_ID_SUFFIX, WRAPPER_END_ID_SUFFIX, FLOW_TO_AUTOMATION_SUFFIX,
            WRAPPER_START_FLOW_SUFFIX, WRAPPER_END_FLOW_SUFFIX);

    public static String twinMessageName(String twinActivityId) {
        return TWIN_MESSAGE_PREFIX + twinActivityId;
    }

    // Derived from the synchronization point's id so TwinAutomationDelegate can recover it.
    public static String automationTaskId(String twinActivityId) {
        return twinActivityId + AUTOMATION_TASK_ID_SUFFIX;
    }

    // getCurrentActivityId() returns the automation task's id; recovers the receive task's id that variables key off.
    public static String synchronizationActivityIdOf(String automationTaskId) {
        return automationTaskId.endsWith(AUTOMATION_TASK_ID_SUFFIX)
                ? automationTaskId.substring(0, automationTaskId.length() - AUTOMATION_TASK_ID_SUFFIX.length())
                : automationTaskId;
    }

    // moveToNode() returns raw type; type parameters are lost after any non-forward step
    @SuppressWarnings("rawtypes")
    public BpmnModelInstance generate(BpmnModelInstance original) {
        Process process = executableProcessOf(original);
        StartEvent start = plainStartEventOf(process);
        rejectReservedIdSuffixes(process);

        List<SequenceFlow> flows = copyableFlows(process);
        Set<String> copied = new LinkedHashSet<>();
        copied.add(start.getId());

        AbstractFlowNodeBuilder cursor = Bpmn.createExecutableProcess(twinProcessId(process))
                .name(twinProcessName(process))
                .startEvent(start.getId());
        cursor = copyGraph(cursor, flows, copied, process.getId(), subProcessWrappedActivityIds(process));

        BpmnModelInstance twin = cursor.done();
        twin.getDocument().registerNamespace(METAML_PREFIX, METAML_NAMESPACE);
        twin.getDefinitions().setId(DEFINITIONS_ID_PREFIX + twinProcessId(process));
        stabilizeMessageIds(twin);
        stabilizeMultiInstanceIds(twin);

        // names, conditions and defaults applied after construction; builder never exposes the flow mid-chain
        copyNodeNames(original, twin, copied);
        copyFlowDetails(twin, flows);
        copyDefaultFlows(process, twin);
        copyMetamlExtensions(original, twin, process, copied);
        stripDiagramInterchange(twin);

        return twin;
    }

    // Builder IDs are random per call; stable IDs are required by enableDuplicateFiltering.
    private static void stabilizeMessageIds(BpmnModelInstance twin) {
        for (org.camunda.bpm.model.bpmn.instance.Message message
                : twin.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Message.class)) {
            message.setId(MESSAGE_ID_PREFIX + message.getName());
        }
    }

    // Same non-determinism as messages; defeats enableDuplicateFiltering on multi-instance models.
    private static void stabilizeMultiInstanceIds(BpmnModelInstance twin) {
        for (MultiInstanceLoopCharacteristics loop : new ArrayList<>(
                twin.getModelElementsByType(MultiInstanceLoopCharacteristics.class))) {
            if (!(loop.getParentElement() instanceof BaseElement owner)) {
                continue;
            }
            loop.setId(MI_LOOP_CHARACTERISTICS_ID_PREFIX + owner.getId());
            LoopCardinality cardinality = loop.getLoopCardinality();
            if (cardinality != null) {
                cardinality.setId(MI_CARDINALITY_ID_PREFIX + owner.getId());
            }
        }
    }

    // Reserved suffixes collide with derived twin ids; reject up front to avoid an opaque deploy failure.
    private static void rejectReservedIdSuffixes(Process process) {
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            for (String suffix : RESERVED_ID_SUFFIXES) {
                if (task.getId().endsWith(suffix)) {
                    throw new IllegalArgumentException("Activity id '" + task.getId() + "' ends with '"
                            + suffix + "', which the twin generator reserves for its own derived ids; "
                            + "rename the activity in the original model to build a twin from it");
                }
            }
        }
    }

    private static void stripDiagramInterchange(BpmnModelInstance twin) {
        for (BpmnDiagram diagram : new ArrayList<>(twin.getModelElementsByType(BpmnDiagram.class))) {
            diagram.getParentElement().removeChildElement(diagram);
        }
    }

    private static Process executableProcessOf(BpmnModelInstance model) {
        List<Process> executable = model.getModelElementsByType(Process.class).stream()
                .filter(Process::isExecutable)
                .toList();
        if (executable.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one executable bpmn:process to build a twin "
                    + "from, found " + executable.size());
        }
        return executable.get(0);
    }

    // message/timer start events would require triggering the twin the same way, not implemented
    private static StartEvent plainStartEventOf(Process process) {
        List<StartEvent> starts = process.getChildElementsByType(StartEvent.class).stream()
                .filter(event -> event.getEventDefinitions().isEmpty())
                .toList();
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a twin for process " + process.getId()
                    + ": it has no plain start event");
        }
        if (starts.size() > 1) {
            logger.warn("Process {} has {} plain start events; the twin is built from {} and the rest are "
                    + "left out", process.getId(), starts.size(), starts.get(0).getId());
        }
        return starts.get(0);
    }

    // Boundary timers on the twin fire on their own clock; dropped here with their outgoing flows.
    private static List<SequenceFlow> copyableFlows(Process process) {
        List<SequenceFlow> flows = new ArrayList<>();
        for (SequenceFlow flow : process.getChildElementsByType(SequenceFlow.class)) {
            if (flow.getSource() instanceof BoundaryEvent boundary) {
                logger.info("Twin of process {} leaves out boundary event {} and its flow {}: the twin's "
                        + "own copy would fire on its own clock instead of following the original, "
                        + "which is the twin diverging from the thing it is supposed to mirror",
                        process.getId(), boundary.getId(), flow.getId());
                continue;
            }
            flows.add(flow);
        }
        return flows;
    }

    // Repeated passes because document order puts a join's second input before its branch exists.
    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder copyGraph(AbstractFlowNodeBuilder cursor,
            List<SequenceFlow> flows, Set<String> copied, String processId, Set<String> subProcessWrapped) {
        List<SequenceFlow> pending = new ArrayList<>(flows);
        boolean madeProgress = true;
        while (madeProgress && !pending.isEmpty()) {
            madeProgress = false;
            for (Iterator<SequenceFlow> pass = pending.iterator(); pass.hasNext();) {
                SequenceFlow flow = pass.next();
                FlowNode source = flow.getSource();
                FlowNode target = flow.getTarget();
                if (source == null || target == null || !copied.contains(source.getId())) {
                    continue;
                }
                String moveToId = exitNodeId(source.getId(), subProcessWrapped);
                if (copied.contains(target.getId())) {
                    pass.remove();
                    madeProgress = true;
                    cursor = cursor.moveToNode(moveToId)
                            .sequenceFlowId(flow.getId())
                            .connectTo(target.getId());
                    continue;
                }
                // Hard failure, not silent drop: a twin with a hole is worse than one that fails to generate.
                if (!isSupported(target)) {
                    throw new IllegalArgumentException("Cannot build a twin for process " + processId
                            + ": activity " + target.getId() + " is a " + target.getElementType().getTypeName()
                            + ", which the twin generator does not support. Remove it from the original "
                            + "model, or extend the generator to handle it, before building a twin.");
                }
                pass.remove();
                madeProgress = true;
                cursor = append(cursor.moveToNode(moveToId).sequenceFlowId(flow.getId()), target);
                copied.add(target.getId());
            }
        }
        for (SequenceFlow unreachable : pending) {
            logger.info("Twin leaves out flow {}: nothing reachable from the start event leads into it",
                    unreachable.getId());
        }
        return cursor;
    }

    // moveToNode() is scope-blind; later flows must exit from the wrapper, not the nested receive task.
    private static String exitNodeId(String activityId, Set<String> subProcessWrapped) {
        return subProcessWrapped.contains(activityId) ? wrapperId(activityId) : activityId;
    }

    private static Set<String> subProcessWrappedActivityIds(Process process) {
        Set<String> wrapped = new HashSet<>();
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            if (!(task.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop)) {
                continue;
            }
            LoopCardinality cardinality = loop.getLoopCardinality();
            String cardinalityText = cardinality == null ? null : cardinality.getTextContent();
            if (!isBlank(cardinalityText) && LITERAL_CARDINALITY.matcher(cardinalityText.trim()).matches()) {
                wrapped.add(task.getId());
            }
        }
        return wrapped;
    }

    // InclusiveGateway unsupported: branch conditions on twin variables cause irrecoverable deadlocks.
    // ReceiveTask and ServiceTask joined this list because this generator is now reached from two
    // very different places. It was written for the Workbench-side twin launch, where the models
    // were hand-picked and made of user tasks. It is now ALSO called for every generated Target
    // Harness Platform, against whatever the user actually modelled - and a manufacturing process
    // realistically contains service tasks (the professor's own worked example is a serviceTask with
    // delegateExpression="${calculateInterestService}"). Without ServiceTask here, Generate failed
    // outright for those models; without ReceiveTask, a process could not express "wait here for the
    // twin's answer" at all. Both are exactly what that throw means by "extend the generator to
    // handle it".
    private static boolean isSupported(FlowNode node) {
        if (node instanceof UserTask || node instanceof ReceiveTask || node instanceof ServiceTask
                || node instanceof ExclusiveGateway || node instanceof ParallelGateway) {
            return true;
        }
        return node instanceof EndEvent end && end.getEventDefinitions().isEmpty();
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder append(AbstractFlowNodeBuilder at, FlowNode node) {
        String id = node.getId();
        if (node instanceof UserTask task) {
            return appendSynchronizedActivity(at, task);
        }
        // Receive and service tasks both become the same sync-then-automate pair the twin uses for
        // every activity: the twin waits on its own TwinAdvance_<id> message and then runs the twin
        // automation delegate. That keeps the twin advancing in lockstep with the original under the
        // bridge's control, and - importantly for a service task - means the twin does NOT re-run
        // the original's own delegate, which would execute the real business logic a second time.
        if (node instanceof ReceiveTask || node instanceof ServiceTask) {
            return appendSyncThenAutomate(at, id);
        }
        if (node instanceof ExclusiveGateway) {
            return at.exclusiveGateway(id);
        }
        if (node instanceof ParallelGateway) {
            return at.parallelGateway(id);
        }
        return at.endEvent(id);
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendSynchronizedActivity(AbstractFlowNodeBuilder at, UserTask task) {
        String id = task.getId();
        if (task.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) {
            return appendMultiInstanceSynchronizedActivity(at, id, loop);
        }
        if (task.getLoopCharacteristics() != null) {
            logger.warn("Twin activity {} runs once: the generator only carries multi-instance "
                    + "characteristics over, not a standard loop", id);
        }
        return appendSyncThenAutomate(at, id);
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendSyncThenAutomate(AbstractFlowNodeBuilder at, String id) {
        return at.receiveTask(id)
                .message(twinMessageName(id))
                .sequenceFlowId(flowToAutomationId(id))
                .serviceTask(automationTaskId(id))
                .camundaDelegateExpression(TWIN_DELEGATE_EXPRESSION);
    }

    // Parallel multi-instance needs per-sibling disambiguation; correlate() throws on a shared message name.
    private static final java.util.regex.Pattern LITERAL_CARDINALITY = java.util.regex.Pattern.compile("\\d+");
    private static final java.util.regex.Pattern LITERAL_BOOLEAN =
            java.util.regex.Pattern.compile("(true|false)|\\$\\{\\s*(true|false)\\s*\\}");

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendMultiInstanceSynchronizedActivity(AbstractFlowNodeBuilder at,
            String id, MultiInstanceLoopCharacteristics loop) {
        LoopCardinality cardinality = loop.getLoopCardinality();
        String cardinalityText = cardinality == null ? null : cardinality.getTextContent();
        if (isBlank(cardinalityText) || !LITERAL_CARDINALITY.matcher(cardinalityText.trim()).matches()) {
            // expression cardinality needs variables the twin doesn't have; run once rather than blocking
            logger.warn("Twin activity {} runs once: only a literal loop cardinality is carried over", id);
            return appendSyncThenAutomate(at, id);
        }
        org.camunda.bpm.model.bpmn.builder.MultiInstanceLoopCharacteristicsBuilder multiInstance =
                at.subProcess(wrapperId(id))
                        .embeddedSubProcess()
                        .startEvent(wrapperStartId(id))
                        .sequenceFlowId(wrapperStartFlowId(id))
                        .receiveTask(id).message(twinMessageName(id))
                        .sequenceFlowId(flowToAutomationId(id))
                        .serviceTask(automationTaskId(id)).camundaDelegateExpression(TWIN_DELEGATE_EXPRESSION)
                        .sequenceFlowId(wrapperEndFlowId(id))
                        .endEvent(wrapperEndId(id))
                    .subProcessDone()
                    .multiInstance();
        org.camunda.bpm.model.bpmn.builder.MultiInstanceLoopCharacteristicsBuilder sequenced =
                loop.isSequential() ? multiInstance.sequential() : multiInstance.parallel();
        sequenced = sequenced.cardinality(cardinalityText);
        // only literal boolean completionConditions carried over; variable-driven ones need data the twin lacks
        org.camunda.bpm.model.bpmn.instance.CompletionCondition completionCondition = loop.getCompletionCondition();
        String completionConditionText = completionCondition == null ? null : completionCondition.getTextContent();
        if (!isBlank(completionConditionText) && LITERAL_BOOLEAN.matcher(completionConditionText.trim()).matches()) {
            sequenced = sequenced.completionCondition(completionConditionText);
        } else if (!isBlank(completionConditionText)) {
            logger.warn("Twin activity {} ignores its original completionCondition '{}': only a literal "
                    + "true/false is carried over", id, completionConditionText);
        }
        return sequenced.multiInstanceDone();
    }

    private static String wrapperId(String twinActivityId) {
        return twinActivityId + WRAPPER_ID_SUFFIX;
    }

    private static String wrapperStartId(String twinActivityId) {
        return twinActivityId + WRAPPER_START_ID_SUFFIX;
    }

    private static String wrapperEndId(String twinActivityId) {
        return twinActivityId + WRAPPER_END_ID_SUFFIX;
    }

    private static String flowToAutomationId(String twinActivityId) {
        return twinActivityId + FLOW_TO_AUTOMATION_SUFFIX;
    }

    private static String wrapperStartFlowId(String twinActivityId) {
        return twinActivityId + WRAPPER_START_FLOW_SUFFIX;
    }

    private static String wrapperEndFlowId(String twinActivityId) {
        return twinActivityId + WRAPPER_END_FLOW_SUFFIX;
    }

    private static void copyNodeNames(BpmnModelInstance original, BpmnModelInstance twin,
            Collection<String> copied) {
        for (String id : copied) {
            FlowNode source = original.getModelElementById(id);
            FlowNode target = twin.getModelElementById(id);
            if (source == null || target == null || isBlank(source.getName())) {
                continue;
            }
            target.setName(source.getName());
        }
    }

    private static void copyFlowDetails(BpmnModelInstance twin, List<SequenceFlow> flows) {
        for (SequenceFlow source : flows) {
            SequenceFlow target = twin.getModelElementById(source.getId());
            if (target == null) {
                continue;
            }
            if (!isBlank(source.getName())) {
                target.setName(source.getName());
            }
            ConditionExpression condition = source.getConditionExpression();
            if (condition == null) {
                continue;
            }
            ConditionExpression copy = twin.newInstance(ConditionExpression.class);
            copy.setId(CONDITION_EXPRESSION_ID_PREFIX + source.getId());
            copy.setTextContent(condition.getTextContent());
            target.setConditionExpression(copy);
        }
    }

    private static void copyDefaultFlows(Process process, BpmnModelInstance twin) {
        for (ExclusiveGateway gateway : process.getChildElementsByType(ExclusiveGateway.class)) {
            applyDefault(twin, gateway.getId(), gateway.getDefault());
        }
    }

    private static void applyDefault(BpmnModelInstance twin, String gatewayId, SequenceFlow defaultFlow) {
        if (defaultFlow == null) {
            return;
        }
        Object gateway = twin.getModelElementById(gatewayId);
        SequenceFlow flow = twin.getModelElementById(defaultFlow.getId());
        if (flow == null) {
            logger.warn("Twin gateway {} has no default flow: {} was not copied over",
                    gatewayId, defaultFlow.getId());
            return;
        }
        if (gateway instanceof ExclusiveGateway exclusive) {
            exclusive.setDefault(flow);
        }
    }

    // Raw DOM copy preserves metaml attributes without needing to register the extension schema.
    private static void copyMetamlExtensions(BpmnModelInstance original, BpmnModelInstance twin,
            Process process, Set<String> copied) {
        Set<String> owners = new HashSet<>(copied);
        owners.add(process.getId());

        Document source = (Document) original.getDocument().getDomSource().getNode();
        NodeList declarations = source.getElementsByTagNameNS(METAML_NAMESPACE, "*");
        for (int i = 0; i < declarations.getLength(); i++) {
            if (!(declarations.item(i) instanceof Element declaration)) {
                continue;
            }
            if (!(declaration.getParentNode() instanceof Element holder)
                    || !EXTENSION_ELEMENTS_NAME.equals(holder.getLocalName())
                    || !(holder.getParentNode() instanceof Element owner)) {
                continue;
            }
            String ownerId = owner.getAttribute(ID_ATTRIBUTE);
            String twinOwnerId = process.getId().equals(ownerId) ? twinProcessId(process) : ownerId;
            if (!owners.contains(ownerId) || !(twin.getModelElementById(twinOwnerId) instanceof BaseElement target)) {
                continue;
            }
            attach(twin, target, declaration);
        }
    }

    private static void attach(BpmnModelInstance twin, BaseElement target, Element declaration) {
        ExtensionElements extensions = target.getExtensionElements();
        if (extensions == null) {
            extensions = twin.newInstance(ExtensionElements.class);
            target.setExtensionElements(extensions);
        }
        extensions.getDomElement().appendChild(copyElement(twin.getDocument(), declaration));
    }

    private static DomElement copyElement(DomDocument document, Element source) {
        DomElement copy = document.createElement(source.getNamespaceURI(), source.getLocalName());

        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (!(attributes.item(i) instanceof Attr attribute)
                    || XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            if (attribute.getNamespaceURI() == null) {
                copy.setAttribute(attribute.getName(), attribute.getValue());
            } else {
                copy.setAttribute(attribute.getNamespaceURI(), attribute.getLocalName(), attribute.getValue());
            }
        }

        boolean nested = false;
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                copy.appendChild(copyElement(document, element));
                nested = true;
            }
        }
        if (!nested && !isBlank(source.getTextContent())) {
            copy.setTextContent(source.getTextContent());
        }
        return copy;
    }

    // distinct from original — same id registers the twin as a new version of the same process
    private static String twinProcessId(Process process) {
        return process.getId() + TWIN_PROCESS_ID_SUFFIX;
    }

    private static String twinProcessName(Process process) {
        return isBlank(process.getName())
                ? twinProcessId(process)
                : process.getName() + TWIN_PROCESS_NAME_SUFFIX;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
