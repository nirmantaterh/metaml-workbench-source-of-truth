import { getBusinessObject, is } from "bpmn-js/lib/util/ModelUtil";

// what's allowed to carry metaml data
const DATA_CAPABLE = ["bpmn:Process", "bpmn:Participant", "bpmn:Task", "bpmn:Activity", "bpmn:SubProcess"];

export function canHoldData(element) {
    if (!element) return false;
    return DATA_CAPABLE.some((type) => is(element, type));
}

export function getExtensionElements(bo) {
    return bo && bo.get("extensionElements");
}

export function getDataItemsContainer(element) {
    const bo = getBusinessObject(element);
    const ee = getExtensionElements(bo);
    if (!ee) return null;
    return (ee.get("values") || []).find((v) => is(v, "metaml:DataItems")) || null;
}

export function getDataItems(element) {
    const container = getDataItemsContainer(element);
    return container ? container.get("items") || [] : [];
}

function createModdle(moddle, type, props, parent) {
    const el = moddle.create(type, props || {});
    if (parent) el.$parent = parent;
    return el;
}

// takes the container type and the name of its child list, since agent outputs hang off the same
// extensionElements as the data items and only differ in what they're called
function ensureContainer(modeler, element, containerType, listProperty) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const bo = getBusinessObject(element);

    let ee = getExtensionElements(bo);
    if (!ee) {
        ee = createModdle(moddle, "bpmn:ExtensionElements", { values: [] }, bo);
        modeling.updateModdleProperties(element, bo, { extensionElements: ee });
    }

    let container = (ee.get("values") || []).find((v) => is(v, containerType));
    if (!container) {
        container = createModdle(moddle, containerType, { [listProperty]: [] }, ee);
        modeling.updateModdleProperties(element, ee, {
            values: [...(ee.get("values") || []), container],
        });
    }
    return container;
}

export function addDataItem(modeler, element) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const container = ensureContainer(modeler, element, "metaml:DataItems", "items");
    const item = createModdle(moddle, "metaml:DataItem", { name: "", type: "string", value: "" }, container);
    modeling.updateModdleProperties(element, container, {
        items: [...(container.get("items") || []), item],
    });
    return item;
}

export function updateDataItem(modeler, element, item, props) {
    modeler.get("modeling").updateModdleProperties(element, item, props);
}

export function removeDataItem(modeler, element, item) {
    const container = item.$parent;
    if (!container) return;
    const items = (container.get("items") || []).filter((i) => i !== item);
    modeler.get("modeling").updateModdleProperties(element, container, { items });
}

// An agent reports whatever its catalog entry says it reports, and the backend lands each of
// those on the process as agentOutput_<activityId>_<outputName>. That name is unambiguous but
// long, and a gateway condition has to be typed by hand. A declaration here says "also publish
// this output as <variable>", which is how a model gets a short name to branch on without
// anybody writing Java for it.
const AGENT_OUTPUT_CAPABLE = ["bpmn:Task", "bpmn:Activity", "bpmn:SubProcess"];

// deliberately not gated on the task already carrying the agentExecutionDelegate listener.
// canHoldData next door goes on element type alone, and a declaration is as inert as a data item
// until something runs against it, so making the section come and go with unrelated wiring would
// only get in the way while a model is half built.
export function canDeclareAgentOutputs(element) {
    if (!element) return false;
    return AGENT_OUTPUT_CAPABLE.some((type) => is(element, type));
}

export function getAgentOutputsContainer(element) {
    const bo = getBusinessObject(element);
    const ee = getExtensionElements(bo);
    if (!ee) return null;
    return (ee.get("values") || []).find((v) => is(v, "metaml:AgentOutputs")) || null;
}

export function getAgentOutputs(element) {
    const container = getAgentOutputsContainer(element);
    return container ? container.get("outputs") || [] : [];
}

export function addAgentOutput(modeler, element) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const container = ensureContainer(modeler, element, "metaml:AgentOutputs", "outputs");
    const output = createModdle(moddle, "metaml:AgentOutput", { name: "", variable: "" }, container);
    modeling.updateModdleProperties(element, container, {
        outputs: [...(container.get("outputs") || []), output],
    });
    return output;
}

export function updateAgentOutput(modeler, element, output, props) {
    modeler.get("modeling").updateModdleProperties(element, output, props);
}

export function removeAgentOutput(modeler, element, output) {
    const container = output.$parent;
    if (!container) return;
    const outputs = (container.get("outputs") || []).filter((o) => o !== output);
    modeler.get("modeling").updateModdleProperties(element, container, { outputs });
}

// only these four declare `default` in the moddle. do NOT widen to bpmn:Gateway - on a
// parallel one it serialises as default="[object Object]" and the whole file is invalid.
const DEFAULT_FLOW_SOURCES = [
    "bpmn:ExclusiveGateway",
    "bpmn:InclusiveGateway",
    "bpmn:ComplexGateway",
    "bpmn:Activity",
];

// only worth offering if the source can hold a default and actually forks
export function canBeDefaultFlow(element) {
    if (!is(element, "bpmn:SequenceFlow")) return false;
    const source = element.source;
    if (!source) return false;
    if (!DEFAULT_FLOW_SOURCES.some((type) => is(source, type))) return false;
    const outgoing = (source.outgoing || []).filter((c) => is(c, "bpmn:SequenceFlow"));
    return outgoing.length >= 2;
}

// the default lives on the SOURCE, not on the flow
export function isDefaultFlow(element) {
    if (!canBeDefaultFlow(element)) return false;
    const sourceBo = getBusinessObject(element.source);
    const flowBo = getBusinessObject(element);
    return sourceBo.get("default") === flowBo;
}

// setting a new one clears the old, there's only ever one ref per source
export function setDefaultFlow(modeler, element, isDefault) {
    if (!canBeDefaultFlow(element)) return;
    const flowBo = getBusinessObject(element);
    modeler.get("modeling").updateProperties(element.source, { default: isDefault ? flowBo : null });
}
