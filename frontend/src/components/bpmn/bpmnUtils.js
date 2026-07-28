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
