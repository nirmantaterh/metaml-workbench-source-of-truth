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

// Builds the twin's own BPMN out of the original's. Up to now "the twin" was a second instance of
// the very same definition, which is why its token could never do anything on its own: every
// activity in it was a user task waiting for a human nobody was ever going to send there.
//
// The twin we generate here is the same process with the human taken out of it. Each user task
// becomes a pair: a receive task that does nothing but wait for AutoBridgeTrigger to correlate a
// message of its own, followed immediately by a service task that runs the project's automation.
// Synchronization and automation are deliberately two different elements rather than one task
// wearing both hats - the receive task answers "is the twin allowed to continue", the service
// task answers "what should it do now that it can". Both execute inside the one correlate()
// command AutoBridgeTrigger issues once the original has committed the matching step: the service
// task carries no async marker, so nothing hands off to the job executor between them, and the
// twin lands on its next receive task in the same breath. That is what makes the two instances
// move together rather than the twin racing ahead.
//
// The first shape of this used an asyncBefore service task on its own and had the bridge run the
// parked job. It worked, but only with camunda.bpm.job-execution.enabled=false, because the job
// executor picks a parked job up within a second or two on its own - and switching the executor
// off took the original's boundary timers and Camunda's history cleanup down with it. A receive
// task is a wait state, not a job: it lives in ACT_RU_EXECUTION with an event subscription beside
// it and there is no row in ACT_RU_JOB for the executor to find, so the executor can be left
// exactly as Camunda ships it. Measured both ways before choosing, and the service task that runs
// the automation now stays synchronous for the same reason - an asyncBefore here would reopen the
// exact hole the receive task exists to close.
//
// The receive task keeps the original activity's id exactly as it was. ActivityLink,
// findTwinActivityId, isTwinWaitingAt, and every twinAutomation_<id>/evolvedAgent_<id> variable
// name already key off that id, and none of them needed to change for this split -
// automationTaskId derives the service task's id from it instead of tracking a second,
// independent id, and synchronizationActivityIdOf is the one place that mapping is undone.
//
// A sequential multi-instance user task gets the same [receive, service] pair, but wrapped in an
// embedded sub-process rather than left as two plain flow nodes: Camunda's multi-instance
// characteristics attach to exactly one activity, and there is no standard way to make "visit N
// times" span two sequential nodes without a sub-process scope around them. loopCounter and the
// rest of the multi-instance body's built-in variables are still visible inside that scope to
// both the receive and the service task, same as they were to the single task before this split -
// proven with a throwaway probe (two correlated visits, asserting the service task read
// loopCounter 0 then 1 and the outer flow only continued once both visits finished) before this
// was written, not assumed.
@Component
public class TwinModelGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TwinModelGenerator.class);

    // same URI AgentOutputDeclarations reads, and for the same reason there's no moddle type here
    // either: two string attributes don't pay for a registered extension
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
    // the flows this split introduces that have no counterpart in the original to inherit an id
    // from - left to the builder they'd get a fresh random one every generate() call, the same
    // non-determinism that used to defeat enableDuplicateFiltering on relaunch (see stabilizeMessageIds)
    private static final String FLOW_TO_AUTOMATION_SUFFIX = "_to_automate";
    private static final String WRAPPER_START_FLOW_SUFFIX = "_sync_start_flow";
    private static final String WRAPPER_END_FLOW_SUFFIX = "_sync_end_flow";

    // stable replacements for the random ids the builder would otherwise hand these element kinds
    // on every generate() call - see stripDiagramInterchange's comment for why it matters
    private static final String DEFINITIONS_ID_PREFIX = "Definitions_";
    private static final String CONDITION_EXPRESSION_ID_PREFIX = "ConditionExpression_";
    private static final String MESSAGE_ID_PREFIX = "Message_";
    private static final String MI_LOOP_CHARACTERISTICS_ID_PREFIX = "MultiInstance_";
    private static final String MI_CARDINALITY_ID_PREFIX = "LoopCardinality_";

    // every id this generator hands out itself, so an original activity whose own id already ends
    // in one of them can be rejected instead of silently colliding with its own derived twin ids -
    // an original "Task_A" alongside "Task_A_automate" would otherwise both produce a twin element
    // id "Task_A_automate", which fails deployment with an opaque duplicate-id error
    private static final List<String> RESERVED_ID_SUFFIXES = List.of(AUTOMATION_TASK_ID_SUFFIX,
            WRAPPER_ID_SUFFIX, WRAPPER_START_ID_SUFFIX, WRAPPER_END_ID_SUFFIX, FLOW_TO_AUTOMATION_SUFFIX,
            WRAPPER_START_FLOW_SUFFIX, WRAPPER_END_FLOW_SUFFIX);

    // The name AutoBridgeTrigger correlates to move this activity on. Built here rather than in the
    // service so the two sides can't drift; every correlation is scoped to a single twin instance
    // as well, so two models that happen to share an activity id are no problem.
    public static String twinMessageName(String twinActivityId) {
        return TWIN_MESSAGE_PREFIX + twinActivityId;
    }

    // The automation service task's id, derived from the synchronization point's id rather than
    // tracked independently. Public so TwinAutomationDelegate can go the other way (see
    // synchronizationActivityIdOf) without the two classes needing a second mapping to stay in sync.
    public static String automationTaskId(String twinActivityId) {
        return twinActivityId + AUTOMATION_TASK_ID_SUFFIX;
    }

    // TwinAutomationDelegate only ever runs as a generated automation task's delegate, so the
    // suffix is always there; this is what recovers the id every twinAutomation_<id>/
    // evolvedAgent_<id> variable is actually named after, since execution.getCurrentActivityId()
    // inside the delegate now returns the automation task's id, not the synchronization point's.
    public static String synchronizationActivityIdOf(String automationTaskId) {
        return automationTaskId.endsWith(AUTOMATION_TASK_ID_SUFFIX)
                ? automationTaskId.substring(0, automationTaskId.length() - AUTOMATION_TASK_ID_SUFFIX.length())
                : automationTaskId;
    }

    // raw builder on purpose: moveToNode() is declared to return the raw type, so the type
    // parameters are gone the moment we stop walking forwards, which is most of this class
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
        // the builder hands the definitions root a random id same as everything else covered below
        twin.getDefinitions().setId(DEFINITIONS_ID_PREFIX + twinProcessId(process));
        stabilizeMessageIds(twin);
        stabilizeMultiInstanceIds(twin);

        // Names, conditions and default flows all land here rather than mid-chain. The builder
        // hands back a node builder, never the sequence flow it just made, so defaultFlow() has
        // nothing to point at while the chain is still running - look the flow up by id afterwards
        // and set it on the gateway directly.
        copyNodeNames(original, twin, copied);
        copyFlowDetails(twin, flows);
        copyDefaultFlows(process, twin);
        copyMetamlExtensions(original, twin, process, copied);
        stripDiagramInterchange(twin);

        return twin;
    }

    // Every element handled by this method and stripDiagramInterchange below got a fresh random id
    // from the builder on every call - the definitions root, each conditionExpression newInstance()
    // in copyFlowDetails, each bpmn:message .message(name) creates one of on a receive task, and a
    // whole BPMNDI diagram (one BPMNShape/BPMNEdge per node/flow) the builder adds whether asked for
    // or not. That randomness is why relaunching the very same model kept deploying a new twin
    // definition version instead of reusing the last one: enableDuplicateFiltering compares
    // deployment resources byte for byte, and no two generate() calls ever produced the same bytes.
    // Proven with a probe that called generate() twice on one definition and diffed the output -
    // PROBE9 in ZzMechanismProbeTest, kept as the record of this before that file's deleted.
    //
    // The diagram carries no functional weight - nothing ever opens the twin's definition in a
    // modeler, the engine does not read it - so it is dropped rather than made deterministic; one
    // less thing to keep in sync with the graph above it. Everything else here is read by the
    // engine (message correlation matches by name, not id, but the id still has to be stable for
    // duplicate filtering to see two deploys as the same resource), so those get a stable id
    // instead of being removed.
    private static void stabilizeMessageIds(BpmnModelInstance twin) {
        for (org.camunda.bpm.model.bpmn.instance.Message message
                : twin.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Message.class)) {
            message.setId(MESSAGE_ID_PREFIX + message.getName());
        }
    }

    // Same class of bug as stabilizeMessageIds, caught by an adversarial review of the sync/
    // automation split rather than assumed fixed just because the other elements were: the
    // multiInstanceLoopCharacteristics and loopCardinality the builder creates for the wrapper
    // sub-process also get a fresh random id every call, which defeats enableDuplicateFiltering on
    // any model with a multi-instance activity exactly the way the other elements used to.
    // Verified by generating grad-admission-review.bpmn's twin twice and diffing the XML before and
    // after this fix, the same way the original bug was proven.
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

    // Also caught by that same review: an original activity id ending in one of this generator's
    // own reserved suffixes would silently collide with its own derived twin ids (a "Task_A" next
    // to a "Task_A_automate" both produce a twin element id "Task_A_automate") and fail deployment
    // with an opaque duplicate-id error instead of a clear one. Rejected up front instead.
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

    // a none start event, which is the only kind either example model has. A message or timer
    // start would need the twin to be triggered the same way the original was, and nothing in the
    // workbench does that yet.
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

    // Boundary events still don't come over, though the reason changed when the twin activity
    // became a wait state. A timer on the twin's copy of Task_Approve would now genuinely fire,
    // and that's exactly the problem: it would send the twin down the escalation branch on its own
    // clock while the original was still sitting on the approval, which is the twin diverging from
    // the thing it is supposed to be mirroring. The original's timeout is bridged over like any
    // other activity it reaches. Dropping the event drops its outgoing flow too, and with it
    // whatever was only reachable that way.
    private static List<SequenceFlow> copyableFlows(Process process) {
        List<SequenceFlow> flows = new ArrayList<>();
        for (SequenceFlow flow : process.getChildElementsByType(SequenceFlow.class)) {
            if (flow.getSource() instanceof BoundaryEvent boundary) {
                // Phase 9/10 red team finding: this used to say "the twin's activity finishes
                // inside a single job, so nothing attached to it can fire" - the stale, pre-Receive
                // Task rationale from when the twin's activity really was an async job. The
                // receive task is a genuine wait state now, so a boundary timer WOULD fire; that's
                // exactly why it's still dropped (see the class comment above copyableFlows).
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

    // Repeated passes rather than a single walk: the flows come back in document order, and a join
    // gateway's second incoming flow is often declared before the branch that feeds it exists. Each
    // pass takes whatever has become reachable, and we stop when a pass adds nothing.
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
                // Phase 7 red team finding W2: this used to log a warning and quietly drop the
                // target (and everything only reachable through it), which let a twin deploy with a
                // hole in it that nobody had to notice. A twin missing part of the workflow it is
                // supposed to mirror is worse than one that fails to generate at all, so an
                // Implementation Gap here is a hard failure with a precise diagnostic instead - the
                // one exception is boundary events, which are stripped earlier in copyableFlows for
                // a documented architectural reason, not because the generator doesn't know them.
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

    // Phase 10 red team finding: a multi-instance activity's receive task deliberately keeps the
    // original activity's id (see appendMultiInstanceSynchronizedActivity), but that receive task
    // lives INSIDE the wrapper sub-process, not at the top level. moveToNode() does a flat,
    // scope-blind search by id, so a later flow leaving that same activity (its ordinary next step)
    // would find the nested receive task and keep building from inside the wrapper - nesting
    // everything downstream inside the multi-instance loop and leaving the receive task with a
    // second, illegitimate outgoing flow. Empirically confirmed against grad-admission-review.bpmn's
    // twin before this fix: Gateway_MajorityApproved and EndEvent_Admitted both ended up nested
    // inside <subProcess id="Task_CommitteeReview_sync">. The wrapper sub-process's own id is the
    // correct node to continue building from - it's the one at the top level, a sibling to
    // everything else, exactly where the multi-instance body's single combined exit point is.
    private static String exitNodeId(String activityId, Set<String> subProcessWrapped) {
        return subProcessWrapped.contains(activityId) ? wrapperId(activityId) : activityId;
    }

    // Precomputed once, before the graph walk, from the same literal-cardinality test
    // appendMultiInstanceSynchronizedActivity itself uses to decide whether an activity gets the
    // sub-process wrapper - an activity whose cardinality falls back to a single plain visit was
    // never wrapped, so its own id is already the correct exit point and needs no remapping.
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

    // Phase 9/10 red team finding: Inclusive Gateway was added here in Phase 7.5 (W2) and turned
    // out to be genuinely unsafe, not merely unsupported. Its outgoing conditions get copied
    // verbatim, but they're evaluated against the TWIN's own, separately-populated variables - if
    // the twin's split activates a branch the original's split didn't, that branch becomes a
    // receive task waiting on a message the bridge can structurally never send, and the twin's own
    // correctly-functioning inclusive join waits on it forever. Unlike Exclusive Gateway (one
    // branch, still terminates) or Parallel Gateway (no data-dependence, branches always agree),
    // Inclusive Gateway combines both properties, which is exactly what makes a mismatch fatal
    // instead of just wrong. Reproduced empirically (a twin whose inclusive split diverged from a
    // simulated original left the instance permanently parked, never ENDED) before reverting this.
    // Fixing it properly would mean communicating which flows the original's gateway actually took
    // across the bridge and driving the twin's split from that - a new synchronization concept the
    // frozen Runtime Architecture doesn't have room for, not a small correction. Falling back to the
    // same fail-fast policy every other unsupported construct already gets (below) is the smallest
    // correction that removes the deadlock risk without inventing one.
    private static boolean isSupported(FlowNode node) {
        if (node instanceof UserTask || node instanceof ExclusiveGateway || node instanceof ParallelGateway) {
            return true;
        }
        // an end event carrying an error/escalation/terminate definition is a different animal and
        // nothing here knows what the twin should do with it
        return node instanceof EndEvent end && end.getEventDefinitions().isEmpty();
    }

    // Names are set in a later pass, so this only has to get the type and the camunda attributes
    // right. The message is the whole point of the exercise: without a wait state the twin runs
    // from its start event straight to an end event the moment it is created, and there is no
    // token left anywhere to keep in step with the original.
    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder append(AbstractFlowNodeBuilder at, FlowNode node) {
        String id = node.getId();
        if (node instanceof UserTask task) {
            return appendSynchronizedActivity(at, task);
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

    // The plain (non-looped) case: a receive task waiting on this activity's own message, straight
    // into the service task that runs its automation. Both stay in the single correlate() command.
    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendSyncThenAutomate(AbstractFlowNodeBuilder at, String id) {
        return at.receiveTask(id)
                .message(twinMessageName(id))
                .sequenceFlowId(flowToAutomationId(id))
                .serviceTask(automationTaskId(id))
                .camundaDelegateExpression(TWIN_DELEGATE_EXPRESSION);
    }

    // Both sequential and parallel multi-instance come over as an embedded sub-process wrapping
    // the same [receive, service] pair, because Camunda's multi-instance characteristics can't
    // span two sequential nodes on their own, only wrap one activity (here, the sub-process).
    // This matters for the grad model, whose committee review is the one activity the original
    // visits more than once - without it the twin would end after visit 1 and there would be
    // nothing left to bridge visits 2 and 3 onto.
    //
    // Parallel needs one thing sequential doesn't: correlate() throws the instant more than one
    // execution matches a message name, and a parallel activity's N siblings all wait on the
    // identical name at once. advanceTwinActivity resolves that ambiguity itself, by loopCounter,
    // using messageEventReceived(name, executionId) to target one sibling directly - proven
    // empirically (three parallel siblings, each released individually by its own execution id,
    // correlate()'s two variable-matching options both failing to disambiguate first) before
    // lifting the restriction here that used to fall every parallel activity back to one visit.
    private static final java.util.regex.Pattern LITERAL_CARDINALITY = java.util.regex.Pattern.compile("\\d+");
    // accepts a bare literal (matching how loopCardinality's own literal form is written in this
    // codebase's fixtures, e.g. "3" not "${3}") or the same value wrapped as a trivial EL literal
    private static final java.util.regex.Pattern LITERAL_BOOLEAN =
            java.util.regex.Pattern.compile("(true|false)|\\$\\{\\s*(true|false)\\s*\\}");

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendMultiInstanceSynchronizedActivity(AbstractFlowNodeBuilder at,
            String id, MultiInstanceLoopCharacteristics loop) {
        LoopCardinality cardinality = loop.getLoopCardinality();
        String cardinalityText = cardinality == null ? null : cardinality.getTextContent();
        if (isBlank(cardinalityText) || !LITERAL_CARDINALITY.matcher(cardinalityText.trim()).matches()) {
            // a collection-driven or expression cardinality needs a variable the twin doesn't
            // have - one visit is better than a twin that can't be moved
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
        // Phase 9/10 red team finding: completionCondition (BPMN's own early-exit signal for
        // multi-instance - e.g. stop once a majority has voted) used to be silently dropped, with
        // no warning, unlike the non-literal-cardinality fallback right above, which does warn.
        // The twin always ran every literal-cardinality visit regardless of what the original's own
        // completionCondition said, a genuine multi-instance completion-semantics divergence. Only
        // copied when it's a fixed boolean literal the twin can evaluate on its own, the same
        // literal-only restriction cardinality already has, for the same reason: a variable-driven
        // condition needs data the twin process instance doesn't carry.
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
            // same reason the definitions root gets one in generate(): newInstance() otherwise
            // hands this a fresh random id every call
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

    // Same situation AgentOutputDeclarations is in - metaml elements arrive as untyped
    // ModelElementInstances, so there's nothing to copy through the model API. Working off the raw
    // document on the way in and DomDocument.createElement on the way out keeps the copy faithful
    // without having to know what attributes any particular metaml element carries.
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
            // anything deeper is a child of one of these and comes over with its parent
            if (!(declaration.getParentNode() instanceof Element holder)
                    || !EXTENSION_ELEMENTS_NAME.equals(holder.getLocalName())
                    || !(holder.getParentNode() instanceof Element owner)) {
                continue;
            }
            // the process keeps its id, the twin's process doesn't, so map that one across
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
        // only a leaf can meaningfully carry text; on a parent it's just the whitespace between
        // its children
        if (!nested && !isBlank(source.getTextContent())) {
            copy.setTextContent(source.getTextContent());
        }
        return copy;
    }

    // deliberately not the original's process id. Deploying under the same id would file the twin
    // as a second version of the original's definition, and cockpit would show one process key
    // flipping between a human diagram and an automated one depending on which version you opened.
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
