import React, { useEffect, useRef, useState, useCallback } from "react";
import { Button, Form } from "react-bootstrap";

import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import "bpmn-js/dist/assets/bpmn-js.css";

import TokenSimulationModule from "bpmn-js-token-simulation";
import "bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css";

import metamlModdle from "../../components/bpmn/moddle/metamlModdle.json";
import camundaModdle from "camunda-bpmn-moddle/resources/camunda.json";
import defaultDiagram from "../../components/bpmn/defaultDiagram";
import DataPanel from "../../components/bpmn/DataPanel";
import "../../components/bpmn/BpmnEditor.css";

// the real Camunda properties panel - Implementation type, Listeners, Field Injections, etc,
// same as the Camunda Modeler desktop app, not our own custom sidebar
import {
    BpmnPropertiesPanelModule,
    BpmnPropertiesProviderModule,
    CamundaPlatformPropertiesProviderModule,
} from "bpmn-js-properties-panel";
import "@bpmn-io/properties-panel/assets/properties-panel.css";
import camundaPlatformBehaviors from "camunda-bpmn-js-behaviors/lib/camunda-platform";

import {
    saveModel,
    launchModel,
    getModel,
    getTwin,
    connectActivity,
    evolveActivity,
    bridgeActivity,
    completeCurrentTasks,
    getGovernancePolicy,
    updateGovernancePolicy,
    getGovernanceUsage,
} from "../../services/workbench/WorkbenchService";

// not connectable. everything else (tasks, events, gateways) can link to a twin.
const NON_ACTIVITY_TYPES = ["bpmn:Process", "bpmn:Collaboration", "bpmn:SequenceFlow"];

// comes back shaped like a denial (approved:false + reason) but really means "already done"
const BRIDGE_ALREADY_FORWARDED_REASON = "Activity event already forwarded to twin";

// throws on a cold first paint (canvas has no layout yet) - zoom is cosmetic so just eat it
function fitViewport(modeler) {
    try {
        modeler.get("canvas").zoom("fit-viewport");
    } catch (e) {
        // keep whatever zoom we have
    }
}

const ModelPage = () => {
    const canvasRef = useRef(null);
    const propertiesPanelRef = useRef(null);
    const modelerRef = useRef(null);
    const fileInputRef = useRef(null);

    const [modelName, setModelName] = useState("New Process");
    // only Load reads this back, Deploy always saves a fresh copy
    const [loadId, setLoadId] = useState("");
    // editable because twin ids don't survive a backend restart - paste the old one back in
    const [twinId, setTwinId] = useState("");
    // the one type the demo node manager always knows
    const [agentType, setAgentType] = useState("validator");
    const [twinLog, setTwinLog] = useState([]);
    const [selected, setSelected] = useState(null);
    // bumped on command-stack changes, otherwise the sidebar never re-renders
    const [, setRevision] = useState(0);
    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);

    // --- Governance ---
    const [deniedAgentTypesInput, setDeniedAgentTypesInput] = useState("");
    const [maxEvolutionsInput, setMaxEvolutionsInput] = useState("");
    // keeps Update policy disabled until we've loaded the real denylist once. submitting before
    // that sends [] and quietly wipes it.
    const [policyLoaded, setPolicyLoaded] = useState(false);
    const [governanceResult, setGovernanceResult] = useState(null);
    const [governanceError, setGovernanceError] = useState(null);

    const bump = useCallback(() => setRevision((r) => r + 1), []);

    useEffect(() => {
        let destroyed = false;
        const container = canvasRef.current;
        const modeler = new BpmnModeler({
            container,
            propertiesPanel: { parent: propertiesPanelRef.current },
            moddleExtensions: { metaml: metamlModdle, camunda: camundaModdle },
            additionalModules: [
                TokenSimulationModule,
                BpmnPropertiesPanelModule,
                BpmnPropertiesProviderModule,
                CamundaPlatformPropertiesProviderModule,
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
            .catch((err) => {
                if (!destroyed) setStatus({ type: "err", text: "Could not load diagram: " + err.message });
            });

        return () => {
            destroyed = true;
            modeler.destroy();
            // StrictMode runs this twice in dev on the same node, clear the leftover svg
            if (container) {
                container.innerHTML = "";
            }
        };
    }, [bump]);

    const currentXml = async () => {
        const { xml } = await modelerRef.current.saveXML({ format: true });
        return xml;
    };

    // pass an explicit id right after Deploy, twinId state hasn't flushed yet.
    // TODO: refetching the whole log after every action is dumb, but it's 3 lines and it works
    const refreshTwinLog = async (id) => {
        const t = (id || twinId).trim();
        if (!t) return;
        try {
            const res = await getTwin(t);
            const twin = res.data || res;
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
        } catch (err) {
            // keep whatever we had
        }
    };

    // manual Refresh only - also pulls the diagram back in, since pasting an old twin id
    // otherwise leaves the canvas showing whatever it already had, not that twin's model
    const handleRefreshTwin = async () => {
        const t = twinId.trim();
        if (!t) return;
        setBusy(true);
        try {
            const res = await getTwin(t);
            const twin = res.data || res;
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
            if (twin.modelId) {
                const modelRes = await getModel(twin.modelId);
                const model = modelRes.data || modelRes;
                if (model?.bpmnXml) {
                    await modelerRef.current.importXML(model.bpmnXml);
                    fitViewport(modelerRef.current);
                    setModelName(model.name || "Untitled");
                    setLoadId(model.id || twin.modelId);
                }
            }
        } catch (err) {
            // log/diagram refresh failing shouldn't wipe whatever's already showing
        } finally {
            setBusy(false);
        }
    };

    const handleNew = async () => {
        try {
            await modelerRef.current.importXML(defaultDiagram);
            fitViewport(modelerRef.current);
            setModelName("New Process");
            setLoadId("");
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
            fitViewport(modelerRef.current);
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
            fitViewport(modelerRef.current);
            setModelName(model.name || "Untitled");
            setLoadId(model.id || id);
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

    // never send an id here. backend won't overwrite an existing model, so re-sending the id
    // from the last save broke every Save after the first one. each save is its own version.
    const handleSave = async () => {
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            const res = await saveModel({ name: modelName, bpmnXml });
            const saved = res.data || res;
            if (saved.id) {
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
            // save first, /launch wants a modelId not raw XML. same no-id rule as handleSave.
            const bpmnXml = await currentXml();
            const saveRes = await saveModel({ name: modelName, bpmnXml });
            const saved = saveRes.data || saveRes;
            const id = saved.id;
            if (id) {
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

    // same id serves as both original and twin activity - one definition, two instances
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
            // a 200 is not an approval, that's inside the decision
            const decision = res.data || res;
            // evolve gives back the decision, not the twin, so the log needs its own fetch
            await refreshTwinLog(twin);
            if (decision.approved) {
                setStatus({
                    type: "ok",
                    text: `Evolved "${selectedActivityId}" with ${type}: approved, agent ${decision.agentName || "(unnamed)"}.`,
                });
            } else {
                setStatus({
                    type: "err",
                    text: `Evolve "${selectedActivityId}" with ${type} blocked: ${decision.reason || "no reason given"}`,
                });
            }
        } catch (err) {
            setStatus({ type: "err", text: "Evolve failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const handleBridge = async () => {
        const twin = twinId.trim();
        if (!twin) {
            setStatus({ type: "err", text: "Deploy first (or paste a twin id) before bridging." });
            return;
        }
        if (!selectedActivityId) {
            setStatus({ type: "err", text: "Select the activity on the canvas you want to bridge." });
            return;
        }
        setBusy(true);
        try {
            const res = await bridgeActivity(twin, selectedActivityId);
            const decision = res.data || res;
            await refreshTwinLog(twin);
            if (decision.approved) {
                setStatus({
                    type: "ok",
                    text: `Bridged "${selectedActivityId}": approved, agent ${decision.agentName || "(unnamed)"}.`,
                });
            } else if (decision.reason === BRIDGE_ALREADY_FORWARDED_REASON) {
                // no-op, not a denial. red here made a correct result look broken.
                setStatus({
                    type: "info",
                    text: `Bridge "${selectedActivityId}" already forwarded to the twin earlier, no change (expected on a repeat bridge).`,
                });
            } else {
                setStatus({
                    type: "err",
                    text: `Bridge "${selectedActivityId}" blocked: ${decision.reason || "no reason given"}`,
                });
            }
        } catch (err) {
            setStatus({ type: "err", text: "Bridge failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // evolve/bridge need the activity reached in the original, and the original sits at its
    // first user task until someone completes it. ignores the canvas selection entirely.
    const handleCompleteTasks = async () => {
        const twin = twinId.trim();
        if (!twin) {
            setStatus({ type: "err", text: "Deploy first (or paste a twin id) before completing tasks." });
            return;
        }
        setBusy(true);
        try {
            const res = await completeCurrentTasks(twin);
            const completed = res.data || res;
            const names = Array.isArray(completed) ? completed : [];
            await refreshTwinLog(twin);
            if (names.length === 0) {
                setStatus({
                    type: "info",
                    text: "No open user tasks on the original process instance, nothing to complete (it may have ended, or be waiting on something other than a user task).",
                });
            } else {
                setStatus({
                    type: "ok",
                    text: `Completed ${names.length} open task(s): ${names.join(", ")}. Next activity should be reachable now, go select it.`,
                });
            }
        } catch (err) {
            setStatus({
                type: "err",
                text: "Complete task(s) failed: " + (err.response?.data?.message || err.message),
            });
        } finally {
            setBusy(false);
        }
    };

    // axios err.message is just "Request failed with status code 400", the useful one is buried
    const governanceErrorText = (err) => err.response?.data?.message || err.message;

    const handleViewPolicy = useCallback(async () => {
        setGovernanceError(null);
        setBusy(true);
        try {
            const res = await getGovernancePolicy();
            const policy = res.data || res;
            setGovernanceResult({ label: "Governance policy", body: policy });
            setDeniedAgentTypesInput((policy.deniedAgentTypes || []).join(", "));
            setMaxEvolutionsInput(String(policy.maxEvolutionsPerTwin ?? ""));
            setPolicyLoaded(true);
        } catch (err) {
            setGovernanceResult(null);
            setGovernanceError("View policy failed: " + governanceErrorText(err));
        } finally {
            setBusy(false);
        }
    }, []);

    const handleUpdatePolicy = async () => {
        const deniedAgentTypes = deniedAgentTypesInput.split(",").map((s) => s.trim()).filter(Boolean);
        const maxEvolutionsPerTwin = maxEvolutionsInput === "" ? undefined : Number(maxEvolutionsInput);
        setBusy(true);
        setGovernanceError(null);
        try {
            const res = await updateGovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin);
            const policy = res.data || res;
            setGovernanceResult({ label: "Governance policy (updated)", body: policy });
            setStatus({ type: "ok", text: "Governance policy updated." });
        } catch (err) {
            setGovernanceResult(null);
            setGovernanceError("Update policy failed: " + governanceErrorText(err));
        } finally {
            setBusy(false);
        }
    };

    const handleViewUsage = async () => {
        const twin = twinId.trim();
        setGovernanceError(null);
        setBusy(true);
        try {
            const res = await getGovernanceUsage(twin);
            setGovernanceResult({ label: `Evolution usage for twin ${twin}`, body: res.data || res });
        } catch (err) {
            setGovernanceResult(null);
            setGovernanceError("View usage failed: " + governanceErrorText(err));
        } finally {
            setBusy(false);
        }
    };

    // load policy on mount so the denylist box isn't empty, see policyLoaded above
    useEffect(() => {
        handleViewPolicy();
    }, [handleViewPolicy]);

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
                <Button
                    size="sm"
                    variant="outline-primary"
                    onClick={handleBridge}
                    disabled={busy || !twinId.trim() || !selectedActivityId}
                >
                    Bridge selected activity
                </Button>
                <Button
                    size="sm"
                    variant="outline-success"
                    onClick={handleCompleteTasks}
                    disabled={busy || !twinId.trim()}
                >
                    Complete current task(s)
                </Button>
                <span className="bpmn-status text-muted">
                    {!twinId.trim()
                        ? "Deploy + Twin first"
                        : selectedActivityId
                        ? `Target activity: "${selectedActivityId}"`
                        : "Select an activity on the canvas"}
                </span>
            </div>

            {/* second gate on evolve/bridge, nothing to do with the node manager's catalog */}
            <div className="bpmn-toolbar bpmn-govbar">
                <span className="bpmn-field-label mb-0">Governance</span>
                <Form.Control
                    size="sm"
                    className="bpmn-denied-types"
                    value={deniedAgentTypesInput}
                    onChange={(e) => setDeniedAgentTypesInput(e.target.value)}
                    placeholder="Denied agent types (comma-separated)"
                />
                <Form.Control
                    size="sm"
                    type="number"
                    min="0"
                    className="bpmn-max-evolutions"
                    value={maxEvolutionsInput}
                    onChange={(e) => setMaxEvolutionsInput(e.target.value)}
                    placeholder="Max evolutions/twin"
                />
                <Button size="sm" variant="outline-secondary" onClick={handleViewPolicy} disabled={busy}>
                    View policy
                </Button>
                <Button
                    size="sm"
                    variant="outline-primary"
                    onClick={handleUpdatePolicy}
                    disabled={busy || !policyLoaded}
                >
                    Update policy
                </Button>
                <Button
                    size="sm"
                    variant="outline-secondary"
                    onClick={handleViewUsage}
                    disabled={busy || !twinId.trim()}
                >
                    View usage for this twin
                </Button>
            </div>

            <div className="bpmn-main">
                <div className="bpmn-canvas" ref={canvasRef} />
                <div className="bpmn-sidebar">
                    <div className="bpmn-properties-panel" ref={propertiesPanelRef} />

                    <div className="bpmn-sidebar-header">Data</div>
                    <DataPanel modeler={modelerRef.current} element={selected} />

                    <div className="bpmn-sidebar-header bpmn-log-header">
                        <span>Twin Event Log</span>
                        <Button
                            size="sm"
                            variant="outline-secondary"
                            className="py-0"
                            onClick={handleRefreshTwin}
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

                    <div className="bpmn-sidebar-header">Governance</div>
                    <div className="bpmn-log-section">
                        {governanceError && (
                            <p className="text-danger small mb-0">{governanceError}</p>
                        )}
                        {!governanceError && governanceResult && (
                            <>
                                <div className="small text-muted mb-1">{governanceResult.label}</div>
                                <pre className="bpmn-json mb-0">
                                    {JSON.stringify(governanceResult.body, null, 2)}
                                </pre>
                            </>
                        )}
                        {!governanceError && !governanceResult && (
                            <p className="text-muted small mb-0">
                                Click "View policy" or "View usage for this twin" above.
                            </p>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ModelPage;
