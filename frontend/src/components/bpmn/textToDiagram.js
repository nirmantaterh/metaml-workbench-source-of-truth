// Text-based process capture. Two input styles are supported, auto-detected:
//
// 1. Arrow DSL (precise, for anyone who wants exact control):
//      start -> Review Application -> task:Approve Loan -> end
//
// 2. Natural language (no arrows required, for anyone who just describes the
//    process in a normal sentence):
//      "The employee fills out an expense form, their manager approves it,
//       and then the finance system automatically processes the reimbursement."
//    Split on natural connectors (commas, "then", "and then", "finally", ...),
//    Start/End are added automatically.
//
// Both paths classify each step's task type from keywords via classifyTaskType
// below. This is keyword matching, not real language understanding -- it does
// NOT handle conditionals ("if.../otherwise...") and refuses that input
// explicitly (natural-language path only) rather than silently generating
// something wrong.
//
// The result is built via bpmn-js's modeling API (elementFactory + modeling),
// per the bpmn-js "modeling-api" example.

const EMPTY_PROCESS_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="true" />
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1" />
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

// Checked most-specific-first: a step could plausibly mention both "system" and
// "script", so the narrower category (Script/Business Rule/Manual) should win over
// the broad "Service" fallback. Anything matching none of these is a User Task --
// the reasonable default for "a person does this."
const BUSINESS_RULE_KEYWORDS = ["business rule", "decision table", "rules engine", "applies the rules", "evaluates the rules"];
const SCRIPT_KEYWORDS = ["runs a script", "executes a script", "runs code", "executes code", "script task"];
const MANUAL_KEYWORDS = ["manually", "physically", "in person", "by hand", "on paper", "offline"];
const SERVICE_KEYWORDS = ["system", "automatically", "automated", "automatic", "database", "chatbot", "bot", "software", "server", "algorithm"];

// Whole-word match only: a naive substring check would fire on keywords buried inside
// unrelated words -- "system" inside "ecosystem"/"subsystem", "server" inside
// "observer", "bot" inside "robot"/"abbot" (a person's name).
function containsKeyword(text, keywords) {
    return keywords.some((keyword) => {
        const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
        return new RegExp(`\\b${escaped}\\b`, "i").test(text);
    });
}

function classifyTaskType(name) {
    if (containsKeyword(name, BUSINESS_RULE_KEYWORDS)) return "bpmn:BusinessRuleTask";
    if (containsKeyword(name, SCRIPT_KEYWORDS)) return "bpmn:ScriptTask";
    if (containsKeyword(name, MANUAL_KEYWORDS)) return "bpmn:ManualTask";
    if (containsKeyword(name, SERVICE_KEYWORDS)) return "bpmn:ServiceTask";
    return "bpmn:UserTask";
}

function parseArrowDsl(text) {
    const segments = text.split("->").map((s) => s.trim()).filter(Boolean);
    if (segments.length === 0) {
        throw new Error('Enter at least one step, e.g. "start -> Review Application -> end"');
    }

    // "start"/"end" only make sense as the first/last step: a sequence flow cannot
    // connect into a start event or out of an end event, so a "start"/"end" in the
    // middle would fail deep inside bpmn-js's connection rules with a confusing error.
    // Catch it here instead with a message that points at the actual problem.
    segments.forEach((segment, index) => {
        const lower = segment.toLowerCase();
        if (lower === "start" && index !== 0) {
            throw new Error('"start" can only be the first step');
        }
        if (lower === "end" && index !== segments.length - 1) {
            throw new Error('"end" can only be the last step');
        }
    });

    return segments.map((segment) => {
        const lower = segment.toLowerCase();
        if (lower === "start") return { type: "bpmn:StartEvent", name: "Start" };
        if (lower === "end") return { type: "bpmn:EndEvent", name: "End" };
        const name = segment.startsWith("task:") ? segment.slice(5).trim() : segment;
        if (!name) {
            throw new Error(`Empty task name in step: "${segment}"`);
        }
        return { type: classifyTaskType(name), name };
    });
}

// Two alternatives: a comma (splits on its own, and also absorbs a connector word right
// after it, e.g. ", and then") or a bare connector word with no comma (e.g. "A then B").
// Without the comma-only branch, a plain Oxford-comma-free list ("A, B, and then C")
// would only split at "and then", leaving "A, B" glued into one step.
// (?!\d) after the comma stops it from splitting a thousands-separated number
// ("charges the customer 1,000 dollars") into two bogus steps.
// (?!-) after each keyword stops "next-day"/"and-such"-style hyphenated compounds from
// matching just the keyword portion and leaving a dangling "-day"/"-such" fragment.
const CONNECTOR_REGEX = /\s*,\s*(?!\d)(?:\b(?:and then|after that|and finally|finally|then|next|and)\b(?!-)\s*)?|\s+\b(?:and then|after that|and finally|finally|then|next|and)\b(?!-)\s*/gi;
const LEADING_FILLER_REGEX = /^(when|once|first|after)\s+/i;
const CONDITIONAL_REGEX = /\b(if|otherwise|else)\b/i;

function capitalize(s) {
    return s.charAt(0).toUpperCase() + s.slice(1);
}

function parseNaturalText(text) {
    const trimmed = text.trim().replace(/\.$/, "");
    if (!trimmed) {
        throw new Error(
            'Describe the process as a sentence, e.g. "the employee submits a form, then a manager approves it."'
        );
    }

    if (CONDITIONAL_REGEX.test(trimmed)) {
        throw new Error(
            'This looks like it has a conditional ("if"/"otherwise"/"else") -- branching isn\'t ' +
            "supported yet, only a straight-line sequence of steps. Try rephrasing it as one path, " +
            "or use the visual editor to add a gateway by hand."
        );
    }

    const rawSteps = trimmed
        .split(CONNECTOR_REGEX)
        .map((s) => s.trim())
        .filter(Boolean);

    if (rawSteps.length === 0) {
        throw new Error("Could not find any steps in that description.");
    }

    const steps = rawSteps.map((step) => {
        const cleaned = step.replace(LEADING_FILLER_REGEX, "").trim();
        return { type: classifyTaskType(cleaned), name: capitalize(cleaned) };
    });

    return [
        { type: "bpmn:StartEvent", name: "Start" },
        ...steps,
        { type: "bpmn:EndEvent", name: "End" },
    ];
}

export function parseTextDescription(text) {
    // Require spaces around the arrow ("Task -> End"), matching how the DSL is actually
    // documented/used, so natural prose that happens to contain a bare "->" (e.g. quoting
    // a data transform like "input->output") doesn't get silently misrouted to the
    // strict DSL parser instead of the natural-language one.
    return text.includes(" -> ") ? parseArrowDsl(text) : parseNaturalText(text);
}

export async function buildDiagramFromText(modeler, text) {
    const nodes = parseTextDescription(text);

    await modeler.importXML(EMPTY_PROCESS_XML);

    const elementFactory = modeler.get("elementFactory");
    const modeling = modeler.get("modeling");
    const canvas = modeler.get("canvas");
    const rootElement = canvas.getRootElement();

    let previousShape = null;
    let x = 200;
    const y = 117;
    let externalTaskCount = 0;

    nodes.forEach((node) => {
        const shape = elementFactory.createShape({ type: node.type });
        modeling.createShape(shape, { x, y }, rootElement);

        const properties = { name: node.name };
        if (node.type === "bpmn:ServiceTask" || node.type === "bpmn:BusinessRuleTask") {
            // Camunda refuses to deploy a bare ServiceTask/BusinessRuleTask -- both
            // require an implementation. "External task" is the simplest valid one for
            // either (no real worker code or DMN decision table needed, just a topic
            // name), so a generated "system does X" / "applies the rules" step actually
            // deploys instead of failing with a missing-implementation error.
            externalTaskCount += 1;
            properties.type = "external";
            properties.topic = `auto-task-${externalTaskCount}`;
        } else if (node.type === "bpmn:ScriptTask") {
            // A ScriptTask must declare a script format and body to be valid BPMN --
            // there's no real script to run here, so a clearly-marked placeholder.
            properties.scriptFormat = "javascript";
            properties.script = "// TODO: implement";
        }
        modeling.updateProperties(shape, properties);

        if (previousShape) {
            modeling.connect(previousShape, shape);
        }
        previousShape = shape;
        x += 150;
    });
}
