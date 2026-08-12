import { useEffect, useRef, useState, useCallback } from "react";

import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import "bpmn-js/dist/assets/bpmn-js.css";

import TokenSimulationModule from "bpmn-js-token-simulation";
import "bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css";

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
        // keep whatever zoom we have
    }
}

// Split out of ModelPage so the new EvolvePage (New scope item 1: Connect/Evolve/Bridge move to
// their own top-level page) can select an activity on the same kind of canvas without duplicating
// the modeler bootstrapping - only ModelPage needs the properties panel wired to an editable
// canvas, but both need "click an activity, know which one is selected".
export default function useBpmnModeler({ withPropertiesPanel = true } = {}) {
    const canvasRef = useRef(null);
    const propertiesPanelRef = useRef(null);
    const modelerRef = useRef(null);

    const [selected, setSelected] = useState(null);
    // bumped on command-stack changes, otherwise a properties-panel-driven edit never re-renders
    // anything reading modelerRef.current
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
                // caller decides what a failed default import means for its own status UI
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

    const importXml = async (xml) => {
        await modelerRef.current.importXML(xml);
        fitViewport(modelerRef.current);
    };

    const currentXml = async () => {
        const { xml } = await modelerRef.current.saveXML({ format: true });
        return xml;
    };

    // The type list above catches an explicitly-selected process/collaboration/flow, but not the
    // element rootAsSelection() falls back to when nothing is selected: on a diagram whose plane
    // has no explicit process element, bpmn-js synthesises an implicit root whose type is none of
    // those, so its generated id ("__implicitroot_2") was reaching callers as though the user had
    // picked an activity. EvolvePage is the only consumer, and it now shows the selected activity
    // as state and switches its actions on it, so "nothing selected" has to actually read as null.
    const isImplicitRoot = selected && typeof selected.id === "string"
        && selected.id.startsWith("__implicitroot");
    const selectedActivityId =
        selected && selected.id && !isImplicitRoot && !NON_ACTIVITY_TYPES.includes(selected.type)
            ? selected.id
            : null;

    return { canvasRef, propertiesPanelRef, modelerRef, selected, selectedActivityId, importXml, currentXml };
}
