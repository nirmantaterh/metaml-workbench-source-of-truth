import { getBusinessObject, is } from "bpmn-js/lib/util/ModelUtil";

// Element types that are allowed to carry MetaML data.
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

// Returns the array of metaml:DataItem moddle elements attached to an element.
export function getDataItems(element) {
    const container = getDataItemsContainer(element);
    return container ? container.get("items") || [] : [];
}

function createModdle(moddle, type, props, parent) {
    const el = moddle.create(type, props || {});
    if (parent) el.$parent = parent;
    return el;
}

// Lazily creates bpmn:extensionElements + metaml:DataItems on the element.
function ensureContainer(modeler, element) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const bo = getBusinessObject(element);

    let ee = getExtensionElements(bo);
    if (!ee) {
        ee = createModdle(moddle, "bpmn:ExtensionElements", { values: [] }, bo);
        modeling.updateModdleProperties(element, bo, { extensionElements: ee });
    }

    let container = (ee.get("values") || []).find((v) => is(v, "metaml:DataItems"));
    if (!container) {
        container = createModdle(moddle, "metaml:DataItems", { items: [] }, ee);
        modeling.updateModdleProperties(element, ee, {
            values: [...(ee.get("values") || []), container],
        });
    }
    return container;
}

export function addDataItem(modeler, element) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const container = ensureContainer(modeler, element);
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

// Native BPMN documentation is used as the free-text "description" of an element.
export function getDocumentation(element) {
    const bo = getBusinessObject(element);
    const docs = bo && bo.get("documentation");
    return docs && docs.length ? docs[0].text || "" : "";
}

export function setDocumentation(modeler, element, text) {
    const modeling = modeler.get("modeling");
    const moddle = modeler.get("moddle");
    const bo = getBusinessObject(element);
    const documentation = text ? [createModdle(moddle, "bpmn:Documentation", { text })] : [];
    modeling.updateModdleProperties(element, bo, { documentation });
}

// Mirrors the element types bpmn-js's own getLabelAttr (lib/util/LabelUtil.js) knows how to
// write a label onto. Anything outside this list -- notably bpmn:Process, which is the default
// selection when the canvas first loads -- makes updateLabel a silent no-op, so the UI must not
// offer an editable Name field for it.
const LABELABLE = [
    "bpmn:FlowElement",
    "bpmn:Participant",
    "bpmn:Lane",
    "bpmn:SequenceFlow",
    "bpmn:MessageFlow",
    "bpmn:DataInput",
    "bpmn:DataOutput",
    "bpmn:TextAnnotation",
    "bpmn:Group",
];

export function canRename(element) {
    if (!element) return false;
    return LABELABLE.some((type) => is(element, type));
}

export function setName(modeler, element, name) {
    if (!canRename(element)) return;
    modeler.get("modeling").updateLabel(element, name);
}

// Element types that actually declare a `default` property in the BPMN moddle, and can
// therefore own a default flow. bpmn-js's renderer is looser than this -- it draws the "//"
// marker for any bpmn:Gateway or bpmn:Activity source -- but the BPMN schema only defines
// `default` on these four. Offering the toggle on a bpmn:ParallelGateway or
// bpmn:EventBasedGateway serialises an undeclared attribute as default="[object Object]",
// producing invalid XML, so the source type must be checked against this list and not
// against the broader bpmn:Gateway.
const DEFAULT_FLOW_SOURCES = [
    "bpmn:ExclusiveGateway",
    "bpmn:InclusiveGateway",
    "bpmn:ComplexGateway",
    "bpmn:Activity",
];

// A sequence flow can only meaningfully be "the default" if its source supports a default
// flow at all and has more than one outgoing sequence flow (i.e. it is actually a
// fork/decision point).
export function canBeDefaultFlow(element) {
    if (!is(element, "bpmn:SequenceFlow")) return false;
    const source = element.source;
    if (!source) return false;
    if (!DEFAULT_FLOW_SOURCES.some((type) => is(source, type))) return false;
    const outgoing = (source.outgoing || []).filter((c) => is(c, "bpmn:SequenceFlow"));
    return outgoing.length >= 2;
}

// The default flow is stored as a reference on the SOURCE element, not on the flow itself.
// This is the same check bpmn-js makes internally in
// bpmn-js/lib/features/modeling/behavior/UnsetDefaultFlowBehavior.js.
export function isDefaultFlow(element) {
    if (!canBeDefaultFlow(element)) return false;
    const sourceBo = getBusinessObject(element.source);
    const flowBo = getBusinessObject(element);
    return sourceBo.get("default") === flowBo;
}

// Setting a new default implicitly clears any previous one, because a source can only hold
// a single `default` reference.
export function setDefaultFlow(modeler, element, isDefault) {
    if (!canBeDefaultFlow(element)) return;
    const flowBo = getBusinessObject(element);
    modeler.get("modeling").updateProperties(element.source, { default: isDefault ? flowBo : null });
}
