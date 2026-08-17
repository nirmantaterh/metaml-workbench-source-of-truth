import { useEffect, useRef, useState, useCallback } from "react";

import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import "bpmn-js/dist/assets/bpmn-js.css";

import TokenSimulationModule from "bpmn-js-token-simulation";
import "bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css";

import { assertRenderableBpmn } from "./renderableBpmn";
import metamlModdle from "./moddle/metamlModdle.json";
import camundaModdle from "camunda-bpmn-moddle/resources/camunda.json";
import defaultDiagram from "./defaultDiagram";

import {
    BpmnPropertiesPanelModule,
    BpmnPropertiesProviderModule,
    CamundaPlatformPropertiesProviderModule,
} from "bpmn-js-properties-panel";
import "@bpmn-io/properties-panel/assets/properties-panel.css";
import camundaPlatformBehaviors from "camunda-bpmn-js-behaviors/lib/camunda-platform";

// not connectable. everything else (tasks, events, gateways) can link to a twin.
const NON_ACTIVITY_TYPES = ["bpmn:Process", "bpmn:Collaboration", "bpmn:SequenceFlow"];

// throws on a cold first paint (canvas has no layout yet) - zoom is cosmetic so just eat it
function fitViewport(modeler) {
    try {
        modeler.get("canvas").zoom("fit-viewport");
    } catch (e) {
        // ignore
    }
}

// Shared canvas hook; handles both the editable (with properties panel) and read-only cases.
export default function useBpmnModeler({ withPropertiesPanel = true } = {}) {
    const canvasRef = useRef(null);
    const propertiesPanelRef = useRef(null);
    const modelerRef = useRef(null);

    const [selected, setSelected] = useState(null);
    // properties-panel edits only change modelerRef; bump revision to re-render consumers
    const [, setRevision] = useState(0);
    const bump = useCallback(() => setRevision((r) => r + 1), []);

    useEffect(() => {
        let destroyed = false;
        const container = canvasRef.current;
        const modeler = new BpmnModeler({
            container,
            propertiesPanel: withPropertiesPanel ? { parent: propertiesPanelRef.current } : undefined,
            moddleExtensions: { metaml: metamlModdle, camunda: camundaModdle },
            additionalModules: [
                TokenSimulationModule,
                ...(withPropertiesPanel
                    ? [BpmnPropertiesPanelModule, BpmnPropertiesProviderModule, CamundaPlatformPropertiesProviderModule]
                    : []),
                camundaPlatformBehaviors,
            ],
        });
        modelerRef.current = modeler;

        const rootAsSelection = () => {
            try {
                return modeler.get("canvas").getRootElement();
            } catch (e) {
                return null;
            }
        };

        const eventBus = modeler.get("eventBus");
        eventBus.on("selection.changed", (e) => {
            const next = e.newSelection && e.newSelection.length ? e.newSelection[0] : rootAsSelection();
            setSelected(next);
        });
        eventBus.on("commandStack.changed", bump);
        eventBus.on("import.done", () => {
            setSelected(rootAsSelection());
            bump();
        });

        modeler
            .importXML(defaultDiagram)
            .then(() => {
                if (!destroyed) fitViewport(modeler);
            })
            .catch(() => {
                // ignore
            });

        return () => {
            destroyed = true;
            modeler.destroy();
            // StrictMode runs this twice in dev on the same node, clear the leftover svg
            if (container) {
                container.innerHTML = "";
            }
        };
    }, [bump, withPropertiesPanel]);

    // guard BEFORE importXML - see assertRenderableBpmn: once bpmn-js has been handed a
    // DI-less model the properties panel is wedged for the rest of the page's life
    const importXml = async (xml) => {
        assertRenderableBpmn(xml);
        await modelerRef.current.importXML(xml);
        fitViewport(modelerRef.current);
    };

    const currentXml = async () => {
        const { xml } = await modelerRef.current.saveXML({ format: true });
        return xml;
    };

    // __implicitroot_* leaks through as if the user picked an activity; "nothing selected" must be null.
    const isImplicitRoot = selected && typeof selected.id === "string"
        && selected.id.startsWith("__implicitroot");
    const selectedActivityId =
        selected && selected.id && !isImplicitRoot && !NON_ACTIVITY_TYPES.includes(selected.type)
            ? selected.id
            : null;

    return { canvasRef, propertiesPanelRef, modelerRef, selected, selectedActivityId, importXml, currentXml };
}
