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

export function setName(modeler, element, name) {
    modeler.get("modeling").updateLabel(element, name);
}
