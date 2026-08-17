// A BPMN file that carries no diagram interchange (bpmndi:BPMNDiagram) is valid to Camunda but
// impossible for bpmn-js to draw: there are no shape bounds or edge waypoints to draw with.
// Handing one to importXML does TWO bad things, and the second is what actually hurt us:
//
//   1. importXML rejects with "no diagram to display" - accurate, but the user never sees it;
//   2. bpmn-js leaves the canvas rooted on a synthetic "__implicitroot_N" placeholder, and that
//      placeholder has NO businessObject at all.
//
// bpmn-js-properties-panel renders whatever root is added (`eventBus.on('root.added', ...)`), and
// its TimerProps reads `getBusinessObject(element).get('eventDefinitions')` BEFORE it checks
// isTimerSupported(element). getBusinessObject falls back to the element itself when there is no
// businessObject, and a plain diagram-js shape has no .get - so it throws "businessObject.get is
// not a function". That masks the real reason with a nonsense message blaming timers in a model
// that contains no timer, and it is sticky: the panel holds on to the dead implicit root, so
// every LATER import - including a perfectly valid file - dies in the same place until the page
// is reloaded. Verified in the browser against this exact stack:
//   getEventDefinition -> getTimerEventDefinition -> TimerProps -> TimerGroup -> getGroups.
//
// Checking before import keeps bpmn-js out of that state altogether: the modeler is never
// touched, so the canvas genuinely is unchanged (which is what the "Open BPMN file" error message
// already promises), and the reported reason is the real one.
//
// Blocking the root.added event instead was tried and rejected: it also suppresses the canvas
// reset diagram-js drives off that same event, which breaks the following import with
// "element <id> already exists".
//
// Kept free of any bpmn-js import on purpose - bpmn-js ships ESM that CRA's jest does not
// transform, so anything importing it cannot be unit tested here.

// [\w.-]+: optional namespace prefix; the trailing class stops "BPMNDiagramReference" matching
const BPMN_DI_PATTERN = /<(?:[\w.-]+:)?BPMNDiagram[\s/>]/;

export function isRenderableBpmn(xml) {
    return typeof xml === "string" && BPMN_DI_PATTERN.test(xml);
}

export function assertRenderableBpmn(xml) {
    if (!isRenderableBpmn(xml)) {
        throw new Error(
            "this BPMN carries no diagram layout information (bpmndi:BPMNDiagram), so there is " +
                "nothing to draw. Open it in a BPMN modeler and save it once to add the layout, " +
                "then try again."
        );
    }
}
