import React, { useEffect, useRef } from "react";
import BpmnModeler from "bpmn-js/lib/Modeler";
import camundaModdleDescriptor from "camunda-bpmn-moddle/resources/camunda.json";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";

// isExecutable="true" is required for Camunda to actually run this process (a
// non-executable process deploys but cannot be instantiated). The default includes a
// real bpmn:userTask, which creates a genuine wait state in Camunda -- without one, a
// bare start->end process would complete instantly, leaving nothing for evolve/connect
// to act on.
export const DEFAULT_BPMN_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="Start">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Activity_Review" name="Review Application">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1" name="End">
      <bpmn:incoming>Flow_2</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Activity_Review" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Activity_Review" targetRef="EndEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="179" y="99" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_Review_di" bpmnElement="Activity_Review">
        <dc:Bounds x="270" y="77" width="100" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="432" y="99" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1_di" bpmnElement="Flow_1">
        <di:waypoint x="215" y="117" />
        <di:waypoint x="270" y="117" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_2_di" bpmnElement="Flow_2">
        <di:waypoint x="370" y="117" />
        <di:waypoint x="432" y="117" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

const BpmnEditor = ({ onModelerReady, onSelectionChanged, height = 500 }) => {
    const containerRef = useRef(null);

    useEffect(() => {
        let destroyed = false;
        const modeler = new BpmnModeler({
            container: containerRef.current,
            moddleExtensions: { camunda: camundaModdleDescriptor },
        });

        modeler.importXML(DEFAULT_BPMN_XML).catch((err) => {
            if (!destroyed) console.error("Failed to import default BPMN diagram", err);
        });

        if (onSelectionChanged) {
            modeler.on("selection.changed", (event) => {
                const selected = event.newSelection && event.newSelection.length === 1
                    ? event.newSelection[0]
                    : null;
                onSelectionChanged(selected);
            });
        }

        if (onModelerReady) onModelerReady(modeler);

        return () => {
            destroyed = true;
            modeler.destroy();
            // React.StrictMode double-invokes this effect in dev, reusing the same
            // container node; clear any leftover SVG so the next mount starts clean.
            if (containerRef.current) {
                containerRef.current.innerHTML = "";
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return <div ref={containerRef} style={{ height, border: "1px solid #ccc" }} />;
};

export default BpmnEditor;
