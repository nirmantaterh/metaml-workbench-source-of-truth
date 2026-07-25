// Reads/writes task metadata as real BPMN/Camunda properties on the model itself, not a
// separate backend record -- so it round-trips through plain Save/Load and travels with
// the exported XML wherever it goes (Cockpit, another tool, a manual deploy). Backed by
// the camunda-bpmn-moddle extension registered in BpmnEditor.js.
//
// - assignee              -> the camunda:assignee attribute. Only valid on bpmn:UserTask
//                             per Camunda's schema (the Assignable mixin only extends
//                             UserTask), so callers should only expose this field there.
// - notes                 -> camunda:properties/camunda:property[name="notes"], Camunda's
//                             generic custom-metadata extension element.
// - inputData/outputData  -> camunda:inputOutput/camunda:inputParameter|outputParameter,
//                             Camunda's real execution-variable mapping mechanism.

const NOTES_PROPERTY_NAME = "notes";
const IO_PARAM_NAME = "data";

// XML imported from anywhere other than this panel (Camunda Modeler, Cockpit, hand-authored)
// routinely has extensionElements/properties/inputOutput with no recognized children -- moddle
// leaves those isMany collections as undefined rather than [], so every read here goes through
// "?." the whole way down instead of assuming a collection that was never populated is an array.
//
// Returns ALL matching containers, not just the first: camunda:Properties/camunda:InputOutput
// are meant to be singular per element, but nothing in the schema stops malformed/hand-authored
// XML from having two. Reading only the first would make the second one's data invisible, and
// writing back a single replacement would silently drop it.
function findExtensions(extensionElements, type) {
    return extensionElements?.values?.filter((value) => value.$type === type) || [];
}

export function readTaskProperties(businessObject) {
    const extensionElements = businessObject.extensionElements;
    const properties = findExtensions(extensionElements, "camunda:Properties").flatMap((p) => p.values || []);
    const inputOutputs = findExtensions(extensionElements, "camunda:InputOutput");
    const inputParameters = inputOutputs.flatMap((io) => io.inputParameters || []);
    const outputParameters = inputOutputs.flatMap((io) => io.outputParameters || []);

    return {
        // Plain property access, not businessObject.get("assignee") -- get() consults the
        // moddle descriptor for the concrete type and can throw for a property that isn't
        // declared there (e.g. a ServiceTask has no assignee). A plain read just returns
        // undefined, which is what callers on non-UserTask elements should see.
        assignee: businessObject.assignee || "",
        notes: properties.find((property) => property.name === NOTES_PROPERTY_NAME)?.value || "",
        inputData: inputParameters.find((param) => param.name === IO_PARAM_NAME)?.value || "",
        outputData: outputParameters.find((param) => param.name === IO_PARAM_NAME)?.value || "",
    };
}

export function writeAssignee(modeler, element, value) {
    modeler.get("modeling").updateProperties(element, { assignee: value || undefined });
}

export function writeNotes(modeler, element, value) {
    writeExtensionProperty(modeler, element, NOTES_PROPERTY_NAME, value);
}

export function writeInputData(modeler, element, value) {
    writeInputOutputParameter(modeler, element, "inputParameters", "camunda:InputParameter", value);
}

export function writeOutputData(modeler, element, value) {
    writeInputOutputParameter(modeler, element, "outputParameters", "camunda:OutputParameter", value);
}

// Every write below rebuilds the changed branch of the extensionElements tree instead of
// mutating the live one in place. bpmn-js's undo snapshots the "old" value of a property by
// reference at the moment the command runs -- if we'd already mutated the live object before
// calling updateProperties, "old" and "new" would be the same object and Ctrl+Z would have
// nothing distinct to restore. Untouched sibling entries (another extension type, the other
// half of an input/output pair) are still reused by reference, just never written to.
//
// Also collapses any duplicate containers of `type` down to the single rebuilt one: since
// findExtensions above already merges every duplicate's values into oldValues/oldInputOutputs
// before this runs, nothing is lost -- a second, invalid container just gets folded into the
// first on the next edit instead of staying split (or silently dropped, as it was before).
function replaceExtension(extensionElements, type, nextChild) {
    const others = (extensionElements?.values || []).filter((v) => v.$type !== type);
    return nextChild ? [...others, nextChild] : others;
}

// Drops extensionElements back to undefined once it holds nothing, so an element nobody
// annotated doesn't pick up a stray <bpmn:extensionElements/> on save.
function commitExtensionElements(modeler, element, values) {
    const moddle = modeler.get("moddle");
    const next = values.length > 0 ? moddle.create("bpmn:ExtensionElements", { values }) : undefined;
    modeler.get("modeling").updateProperties(element, { extensionElements: next });
}

function writeExtensionProperty(modeler, element, name, value) {
    const moddle = modeler.get("moddle");
    const extensionElements = element.businessObject.extensionElements;
    const oldValues = findExtensions(extensionElements, "camunda:Properties").flatMap((p) => p.values || []);

    const nextPropertyValues = oldValues.filter((property) => property.name !== name);
    if (value) nextPropertyValues.push(moddle.create("camunda:Property", { name, value }));

    const nextProperties = nextPropertyValues.length > 0
        ? moddle.create("camunda:Properties", { values: nextPropertyValues })
        : null;

    commitExtensionElements(modeler, element, replaceExtension(extensionElements, "camunda:Properties", nextProperties));
}

function writeInputOutputParameter(modeler, element, listName, paramType, value) {
    const moddle = modeler.get("moddle");
    const extensionElements = element.businessObject.extensionElements;
    const oldInputOutputs = findExtensions(extensionElements, "camunda:InputOutput");
    const oldInputParameters = oldInputOutputs.flatMap((io) => io.inputParameters || []);
    const oldOutputParameters = oldInputOutputs.flatMap((io) => io.outputParameters || []);

    const oldList = listName === "inputParameters" ? oldInputParameters : oldOutputParameters;
    const nextList = oldList.filter((param) => param.name !== IO_PARAM_NAME);
    if (value) nextList.push(moddle.create(paramType, { name: IO_PARAM_NAME, value }));

    const nextInputParameters = listName === "inputParameters" ? nextList : oldInputParameters;
    const nextOutputParameters = listName === "outputParameters" ? nextList : oldOutputParameters;

    const nextInputOutput = (nextInputParameters.length > 0 || nextOutputParameters.length > 0)
        ? moddle.create("camunda:InputOutput", { inputParameters: nextInputParameters, outputParameters: nextOutputParameters })
        : null;

    commitExtensionElements(modeler, element, replaceExtension(extensionElements, "camunda:InputOutput", nextInputOutput));
}
