import { assertRenderableBpmn, isRenderableBpmn } from "./renderableBpmn";
import defaultDiagram from "./defaultDiagram";

// Regression guard for the "businessObject.get is not a function" class of failure.
//
// A BPMN with no bpmndi:BPMNDiagram is valid to Camunda but undrawable by bpmn-js. Handing one to
// importXML leaves the canvas on an "__implicitroot_N" placeholder with no businessObject, which
// bpmn-js-properties-panel then renders - and its TimerProps dereferences
// businessObject.get('eventDefinitions') before checking isTimerSupported, so it throws a message
// about timers for a model that has none, and stays broken for every later import too.
// assertRenderableBpmn stops that XML before bpmn-js ever sees it.
describe("assertRenderableBpmn", () => {
    it("accepts the default diagram the editor boots with", () => {
        expect(() => assertRenderableBpmn(defaultDiagram)).not.toThrow();
    });

    it("accepts DI regardless of the namespace prefix in use", () => {
        expect(() =>
            assertRenderableBpmn('<definitions><bpmndi:BPMNDiagram id="d" /></definitions>')
        ).not.toThrow();
        expect(() =>
            assertRenderableBpmn('<definitions><di:BPMNDiagram id="d"></di:BPMNDiagram></definitions>')
        ).not.toThrow();
        // no prefix at all is legal when bpmndi is the default namespace
        expect(() => assertRenderableBpmn("<definitions><BPMNDiagram/></definitions>")).not.toThrow();
    });

    it("rejects a process-only BPMN and says the layout is what is missing", () => {
        const noDi = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL">
  <bpmn2:process id="p" isExecutable="true"><bpmn2:startEvent id="s" /></bpmn2:process>
</bpmn2:definitions>`;
        expect(() => assertRenderableBpmn(noDi)).toThrow(/bpmndi:BPMNDiagram/);
    });

    // the failure this whole guard exists to prevent must never be reported as a timer problem
    it("never blames timers for a model that has no timer", () => {
        expect(() => assertRenderableBpmn("<definitions><process id='p'/></definitions>")).toThrow(
            /^(?!.*businessObject).*$/
        );
    });

    it("rejects non-string input instead of letting it reach bpmn-js", () => {
        expect(() => assertRenderableBpmn(undefined)).toThrow();
        expect(() => assertRenderableBpmn(null)).toThrow();
    });

    // "BPMNDiagramFoo" must not count as DI
    it("does not match a merely similar element name", () => {
        expect(() => assertRenderableBpmn("<definitions><BPMNDiagramReference/></definitions>")).toThrow();
    });
});

describe("isRenderableBpmn", () => {
    it("mirrors assertRenderableBpmn without throwing", () => {
        expect(isRenderableBpmn(defaultDiagram)).toBe(true);
        expect(isRenderableBpmn("<definitions><process id='p'/></definitions>")).toBe(false);
        expect(isRenderableBpmn(undefined)).toBe(false);
    });
});
