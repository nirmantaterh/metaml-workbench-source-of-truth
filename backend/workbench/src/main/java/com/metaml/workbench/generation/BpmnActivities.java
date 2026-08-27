package com.metaml.workbench.generation;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Scope item 4: the generated app used to expose one generic completion endpoint that every activity went through. Joanna confirmed it has to be one endpoint per activity instead, so something has to answer "which activities does this BPMN actually have". The first cut of this class answered that with "user tasks". That was a type list, and a type list is the wrong abstraction - it says nothing about why a user task qualified. The real question is about execution semantics, not element type: an activity is eligible when the engine parks the token there and will never move it on its own, so something outside the process has to trigger it That is what an endpoint can honestly represent. Everything else either runs on arrival or is driven by the engine, and an endpoint for it would be an endpoint that cannot do anything. This distinction cuts across element types rather than along them, which is the point: a serviceTask carrying camunda:type="external" is eligible, while a serviceTask carrying a delegateExpression is not. Same element type, opposite answers, decided by configuration. Deliberately NOT merged with DelegateClassGenerator's traversal even though both read the same file. That one answers "which delegate beans must exist", keyed on delegateExpression and deduped by class name; two activities sharing one delegateExpression correctly produce one class. Endpoints are the opposite: those same two activities must produce two endpoints.
public final class BpmnActivities {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";

    // How the generated app has to talk to the engine to move this activity along. The endpoint layer stays uniform - one URL per activity, always /<activity>/complete - and this is the only thing that differs underneath, which is what keeps the generator generic while the execution stays faithful to what the activity actually is.
    public enum Trigger {
        // a real human task the engine created; TaskService completes it
        USER_TASK,
        // a wait state with no task behind it; the execution sitting there gets signalled
        RECEIVE_TASK,
        // the engine explicitly never runs these - an external worker must, which is exactly what a generated endpoint is
        EXTERNAL_TASK
    }

    // id is the BPMN element id and the only identity that actually wires anything up - it is what Camunda stores as a task's taskDefinitionKey, which is in turn what MetaML's own governance and twin bookkeeping already use as activityId (see AgentExecutionDelegate). endpointSlug is presentation only: it makes the URL readable, and it is never what the generated handler dispatches on.
    public record Activity(String id, String name, String endpointSlug, String methodSuffix, Trigger trigger) {
    }

    private BpmnActivities() {
    }

    // Queried through the BPMN supertype rather than a list of concrete task types, so a model using an element nobody here thought to enumerate still gets classified by the same rule instead of silently vanishing. Document order, matching DelegateClassGenerator's own convention - the same BPMN has to produce the same endpoints in the same order every run.
    public static List<Activity> eligible(BpmnModelInstance model) {
        List<Activity> activities = new ArrayList<>();
        Set<String> usedSlugs = new LinkedHashSet<>();
        for (org.camunda.bpm.model.bpmn.instance.Activity element
                : model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Activity.class)) {
            Trigger trigger = triggerFor(element);
            if (trigger == null) {
                continue;
            }
            String id = element.getId();
            String slug = disambiguate(preferredSlug(element.getName(), id), usedSlugs);
            activities.add(new Activity(id, element.getName(), slug, toMethodSuffix(slug), trigger));
        }
        return activities;
    }

    // null means "the engine handles this one, an endpoint would be a lie". camunda:type is read as a raw namespaced attribute rather than through ServiceTask's own getCamundaType(), because the external implementation is equally legal on a sendTask or a businessRuleTask - going through the typed getter would mean re-listing exactly the element types this is trying not to depend on. Checked before the element types below because "external" overrides whatever the element would otherwise do: a serviceTask marked external is a wait state, not an inline call.
    private static Trigger triggerFor(org.camunda.bpm.model.bpmn.instance.Activity element) {
        if (EXTERNAL_IMPLEMENTATION.equals(element.getAttributeValueNs(CAMUNDA_NS, "type"))) {
            return Trigger.EXTERNAL_TASK;
        }
        if (element instanceof UserTask) {
            return Trigger.USER_TASK;
        }
        if (element instanceof ReceiveTask) {
            return Trigger.RECEIVE_TASK;
        }
        // Everything else runs the moment the token arrives - a serviceTask with a delegateExpression, a scriptTask, a businessRuleTask - or is a pass-through the engine walks straight through (manualTask, a bare task). A sub-process or call activity is a container: the token stops inside it, at whichever child activity is itself a wait state, and that child gets its own endpoint on its own merits.
        return null;
    }

    // The display name is what a person recognises in the URL, so it wins when it survives slugification. It is free text from the modeler though - it can be blank, it can be pure punctuation, and this repo's own models already carry one with an embedded newline - so the element id (guaranteed present and unique) is the fallback, and a fixed literal backstops even that in case an id somehow slugifies to nothing.
    private static String preferredSlug(String name, String id) {
        String fromName = slugify(name);
        if (!fromName.isEmpty()) {
            return fromName;
        }
        String fromId = slugify(id);
        return fromId.isEmpty() ? "activity" : fromId;
    }

    // ASCII letters and digits only. Character.toLowerCase(char) rather than String.toLowerCase(), which is locale-sensitive and would quietly produce a different URL under a Turkish default locale - the same trap GovernanceServiceImpl already pins with Locale.ROOT. Non-ASCII letters are dropped rather than transliterated: guessing that "ü" means "u" is a judgement call this has no business making, and the id fallback above already covers a name that disappears entirely.
    private static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder slug = new StringBuilder(raw.length());
        boolean pendingSeparator = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                if (pendingSeparator && slug.length() > 0) {
                    slug.append('-');
                }
                slug.append(Character.toLowerCase(c));
                pendingSeparator = false;
            } else {
                pendingSeparator = true;
            }
        }
        return slug.toString();
    }

    // Element ids are unique, but slugs of them need not be - "Task_KYC" and "Task-KYC" both reduce to "task-kyc", and two activities can share a display name outright. Two endpoints mapped to the same path is a startup failure in the generated app, so collisions are resolved here instead, by document order, which keeps the output deterministic.
    private static String disambiguate(String slug, Set<String> usedSlugs) {
        String candidate = slug;
        int suffix = 2;
        while (!usedSlugs.add(candidate)) {
            candidate = slug + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    // Derived from the already-deduplicated slug so that unique slugs guarantee unique Java method names, rather than repeating the collision handling for identifiers.
    private static String toMethodSuffix(String slug) {
        StringBuilder name = new StringBuilder(slug.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            if (c == '-') {
                capitalizeNext = true;
                continue;
            }
            name.append(capitalizeNext ? Character.toUpperCase(c) : c);
            capitalizeNext = false;
        }
        // a slug starting with a digit ("2nd-review") would otherwise render an illegal method name; the prefix keeps it a valid identifier without changing the URL
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, "Activity");
        }
        return name.toString();
    }
}
