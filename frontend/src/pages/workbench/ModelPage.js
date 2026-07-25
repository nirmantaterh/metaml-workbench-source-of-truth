import React, { useEffect, useRef, useState, useCallback } from "react";
import { Button, Form } from "react-bootstrap";

import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import "bpmn-js/dist/assets/bpmn-js.css";

import metamlModdle from "../../components/bpmn/moddle/metamlModdle.json";
import defaultDiagram from "../../components/bpmn/defaultDiagram";
import DataPanel from "../../components/bpmn/DataPanel";
import "../../components/bpmn/BpmnEditor.css";

import {
    saveModel,
    launchModel,
    getModel,
    getTwin,
    connectActivity,
    evolveActivity,
} from "../../services/workbench/WorkbenchService";

// bpmn element types that aren't connectable activities (the process/collaboration root and
// sequence flows). Everything else -- tasks, events, gateways -- can be linked to a twin.
const NON_ACTIVITY_TYPES = ["bpmn:Process", "bpmn:Collaboration", "bpmn:SequenceFlow"];

const ModelPage = () => {
    const canvasRef = useRef(null);
    const modelerRef = useRef(null);
    const fileInputRef = useRef(null);

    const [modelName, setModelName] = useState("New Process");
    // Id of the currently loaded/saved model. Set by Save (from the backend response) and
    // by Load; also the id we deploy in handleDeploy.
    const [modelId, setModelId] = useState("");
    const [loadId, setLoadId] = useState("");
    // Twin handle returned by Deploy; this is the id the connect/evolve endpoints key on.
    const [twinId, setTwinId] = useState("");
    // Agent type requested when evolving an activity. "validator" is the one type the demo
    // node manager's catalog always recognizes; change it to try denied/unknown types.
    const [agentType, setAgentType] = useState("validator");
    // Ordered event log for the current twin, mirrored from GET /twin/{id}.
    const [twinLog, setTwinLog] = useState([]);
    const [selected, setSelected] = useState(null);
    // Bumped on every command-stack change so the (moddle-backed) sidebar re-renders.
    const [, setRevision] = useState(0);
    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);

    const bump = useCallback(() => setRevision((r) => r + 1), []);

    useEffect(() => {
        let destroyed = false;
        const container = canvasRef.current;
        const modeler = new BpmnModeler({
            container,
            moddleExtensions: { metaml: metamlModdle },
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
                if (!destroyed) modeler.get("canvas").zoom("fit-viewport");
            })
            .catch((err) => {
                if (!destroyed) setStatus({ type: "err", text: "Could not load diagram: " + err.message });
            });

        return () => {
            destroyed = true;
            modeler.destroy();
            // React.StrictMode double-invokes this effect in dev, reusing the same
            // container node; clear any leftover SVG so the next mount starts clean.
            if (container) {
                container.innerHTML = "";
            }
        };
    }, [bump]);

    const currentXml = async () => {
        const { xml } = await modelerRef.current.saveXML({ format: true });
        return xml;
    };

    // Pull the twin's ordered event log. Non-fatal: a failed refresh leaves the last-known log
    // and doesn't clobber the main status line. Pass an explicit id after Deploy, where the
    // twinId state hasn't flushed yet.
    const refreshTwinLog = async (id) => {
        const t = (id || twinId).trim();
        if (!t) return;
        try {
            const res = await getTwin(t);
            const twin = res.data || res;
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
        } catch (err) {
            // leave the existing log in place
        }
    };

    const handleNew = async () => {
        try {
            await modelerRef.current.importXML(defaultDiagram);
            modelerRef.current.get("canvas").zoom("fit-viewport");
            setModelName("New Process");
            setModelId("");
            setTwinId("");
            setTwinLog([]);
            setStatus({ type: "info", text: "Started a new model." });
        } catch (err) {
            setStatus({ type: "err", text: err.message });
        }
    };

    const handleImportFile = async (event) => {
        const file = event.target.files && event.target.files[0];
        if (!file) return;
        const text = await file.text();
        try {
            await modelerRef.current.importXML(text);
            modelerRef.current.get("canvas").zoom("fit-viewport");
            setModelName(file.name.replace(/\.(bpmn|xml)$/i, ""));
            setStatus({ type: "ok", text: `Imported ${file.name}.` });
        } catch (err) {
            setStatus({ type: "err", text: "Import failed: " + err.message });
        }
        event.target.value = "";
    };

    const handleLoad = async () => {
        const id = loadId.trim();
        if (!id) {
            setStatus({ type: "err", text: "Enter a model id to load." });
            return;
        }
        setBusy(true);
        try {
            const res = await getModel(id);
            const model = res.data || res;
            if (!model || !model.bpmnXml) {
                throw new Error("Model has no BPMN XML");
            }
            await modelerRef.current.importXML(model.bpmnXml);
            modelerRef.current.get("canvas").zoom("fit-viewport");
            setModelName(model.name || "Untitled");
            setModelId(model.id || id);
            setStatus({ type: "ok", text: `Loaded model "${model.name || id}" (id ${model.id || id}).` });
        } catch (err) {
            setStatus({ type: "err", text: "Load failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const handleDownload = async () => {
        const xml = await currentXml();
        const blob = new Blob([xml], { type: "application/xml" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${modelName || "process"}.bpmn`;
        a.click();
        URL.revokeObjectURL(url);
    };

    const handleSave = async () => {
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            const res = await saveModel({ id: modelId || undefined, name: modelName, bpmnXml });
            const saved = res.data || res;
            if (saved.id) {
                setModelId(saved.id);
                setLoadId(saved.id);
            }
            setStatus({ type: "ok", text: `Saved model "${saved.name || modelName}" (id ${saved.id ?? "?"}).` });
        } catch (err) {
            setStatus({ type: "err", text: "Save failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const handleDeploy = async () => {
        setBusy(true);
        try {
            // Save first so the model is deployed to the engine and we have a modelId to launch
            // (the /launch endpoint takes a modelId, not raw XML).
            const bpmnXml = await currentXml();
            const saveRes = await saveModel({ id: modelId || undefined, name: modelName, bpmnXml });
            const saved = saveRes.data || saveRes;
            const id = saved.id;
            if (id) {
                setModelId(id);
                setLoadId(id);
            }

            const res = await launchModel({ modelId: id });
            const twin = res.data || res;
            setTwinId(twin.id || "");
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
            setStatus({
                type: "ok",
                text: `Deployed "${modelName}" → original instance ${twin.originalProcessId || "?"} + twin instance ${twin.twinProcessId || "?"} (twin id ${twin.id || "?"}).`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Deploy failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // Id of the selected canvas element if it's a connectable activity (task/event/gateway),
    // else null. Used as both the original and (by default) the twin activity id, since the
    // original and twin instances share one process definition and thus identical activity ids.
    const selectedActivityId =
        selected && selected.id && !NON_ACTIVITY_TYPES.includes(selected.type) ? selected.id : null;

    const handleConnect = async () => {
        const twin = twinId.trim();
        if (!twin) {
            setStatus({ type: "err", text: "Deploy first (or paste a twin id) before connecting." });
            return;
        }
        if (!selectedActivityId) {
            setStatus({ type: "err", text: "Select an activity on the canvas to connect to its twin." });
            return;
        }
        setBusy(true);
        try {
            const res = await connectActivity({
                twinProcessId: twin,
                originalActivityId: selectedActivityId,
                twinActivityId: selectedActivityId,
            });
            const updated = res.data || res;
            const count = Array.isArray(updated.activityLinks) ? updated.activityLinks.length : "?";
            setTwinLog(Array.isArray(updated.eventLog) ? updated.eventLog : twinLog);
            setStatus({
                type: "ok",
                text: `Connected activity "${selectedActivityId}" to its twin (twin ${twin}, ${count} link(s) total).`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Connect failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const handleEvolve = async () => {
        const twin = twinId.trim();
        const type = agentType.trim();
        if (!twin) {
            setStatus({ type: "err", text: "Deploy first (or paste a twin id) before evolving." });
            return;
        }
        if (!selectedActivityId) {
            setStatus({ type: "err", text: "Select the activity on the canvas you want to evolve." });
            return;
        }
        if (!type) {
            setStatus({ type: "err", text: "Enter an agent type to request." });
            return;
        }
        setBusy(true);
        try {
            const res = await evolveActivity({
                twinProcessId: twin,
                activityId: selectedActivityId,
                agentType: type,
            });
            // The outer HTTP call succeeding says nothing about approval -- the real answer is
            // in the AgentDecision's approved/reason fields.
            const decision = res.data || res;
            // Evolve returns the decision, not the twin, so pull the updated log separately.
            await refreshTwinLog(twin);
            if (decision.approved) {
                setStatus({
                    type: "ok",
                    text: `Evolved "${selectedActivityId}" with ${type}: approved → agent ${decision.agentName}.`,
                });
            } else {
                setStatus({
                    type: "err",
                    text: `Evolve "${selectedActivityId}" with ${type}: blocked — ${decision.reason}`,
                });
            }
        } catch (err) {
            setStatus({ type: "err", text: "Evolve failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const statusClass =
        status?.type === "err" ? "text-danger" : status?.type === "ok" ? "text-success" : "text-muted";

    return (
        <div className="bpmn-editor">
            <div className="bpmn-toolbar">
                <Form.Control
                    size="sm"
                    className="bpmn-model-name"
                    value={modelName}
                    onChange={(e) => setModelName(e.target.value)}
                    placeholder="Model name"
                />
                <Button size="sm" variant="outline-secondary" onClick={handleNew}>
                    New
                </Button>
                <Button size="sm" variant="outline-secondary" onClick={() => fileInputRef.current.click()}>
                    Import
                </Button>
                <Button size="sm" variant="outline-secondary" onClick={handleDownload}>
                    Download
                </Button>
                <input
                    ref={fileInputRef}
                    type="file"
                    accept=".bpmn,.xml"
                    style={{ display: "none" }}
                    onChange={handleImportFile}
                />
                <Form.Control
                    size="sm"
                    className="bpmn-load-id"
                    value={loadId}
                    onChange={(e) => setLoadId(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && handleLoad()}
                    placeholder="Model id"
                />
                <Button size="sm" variant="outline-secondary" onClick={handleLoad} disabled={busy}>
                    Load
                </Button>
                <div className="spacer" />
                {status && <span className={`bpmn-status ${statusClass}`}>{status.text}</span>}
                <Button size="sm" variant="outline-primary" onClick={handleSave} disabled={busy}>
                    Save
                </Button>
                <Button size="sm" variant="primary" onClick={handleDeploy} disabled={busy}>
                    Deploy + Twin
                </Button>
            </div>

            <div className="bpmn-toolbar bpmn-twinbar">
                <span className="bpmn-field-label mb-0">Twin</span>
                <Form.Control
                    size="sm"
                    className="bpmn-twin-id"
                    value={twinId}
                    onChange={(e) => setTwinId(e.target.value)}
                    placeholder="Deploy to get a twin id"
                />
                <Button
                    size="sm"
                    variant="outline-secondary"
                    onClick={handleConnect}
                    disabled={busy || !twinId.trim() || !selectedActivityId}
                >
                    Connect selected activity
                </Button>
                <Form.Control
                    size="sm"
                    className="bpmn-agent-type"
                    value={agentType}
                    onChange={(e) => setAgentType(e.target.value)}
                    placeholder="Agent type"
                />
                <Button
                    size="sm"
                    variant="outline-primary"
                    onClick={handleEvolve}
                    disabled={busy || !twinId.trim() || !selectedActivityId || !agentType.trim()}
                >
                    Evolve selected activity
                </Button>
                <span className="bpmn-status text-muted">
                    {selectedActivityId
                        ? `Target activity: "${selectedActivityId}"`
                        : "Select an activity on the canvas"}
                </span>
            </div>

            <div className="bpmn-main">
                <div className="bpmn-canvas" ref={canvasRef} />
                <div className="bpmn-sidebar">
                    <div className="bpmn-sidebar-header">Element Details</div>
                    <DataPanel modeler={modelerRef.current} element={selected} />

                    <div className="bpmn-sidebar-header bpmn-log-header">
                        <span>Twin Event Log</span>
                        <Button
                            size="sm"
                            variant="outline-secondary"
                            className="py-0"
                            onClick={() => refreshTwinLog()}
                            disabled={busy || !twinId.trim()}
                        >
                            Refresh
                        </Button>
                    </div>
                    <div className="bpmn-log-section">
                        {!twinId.trim() ? (
                            <p className="text-muted small mb-0">
                                Deploy a model to start a twin and see its event log here.
                            </p>
                        ) : twinLog.length === 0 ? (
                            <p className="text-muted small mb-0">No events logged for this twin yet.</p>
                        ) : (
                            <ol className="bpmn-log-list">
                                {twinLog.map((entry, idx) => (
                                    <li key={idx}>{entry}</li>
                                ))}
                            </ol>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ModelPage;
