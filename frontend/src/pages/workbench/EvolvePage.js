import React, { useCallback, useEffect, useState } from "react";
import { Button, Form } from "react-bootstrap";

import useBpmnModeler from "../../components/bpmn/useBpmnModeler";
import "../../components/bpmn/BpmnEditor.css";

import {
    getModel,
    getTwin,
    launchModel,
    connectActivity,
    evolveActivity,
    bridgeActivity,
    completeCurrentTasks,
    getGovernancePolicy,
    updateGovernancePolicy,
    getGovernanceUsage,
} from "../../services/workbench/WorkbenchService";

// comes back shaped like a denial (approved:false + reason) but really means "already done"
const BRIDGE_ALREADY_FORWARDED_REASON = "Activity event already forwarded to twin";

// New scope item 1: Connect and Evolve move out of the model editor into their own top-level
// page. This is the pre-existing twin (Deploy + Twin / Connect / Evolve / Bridge) workflow,
// relocated as-is - not the newer Model -> Generate -> Launch Spring Boot pipeline, which stays on
// the editor page. Its own canvas, no properties panel - this page only needs to select an
// activity on an already-deployed model's diagram, not edit it.
const EvolvePage = () => {
    const { canvasRef, selectedActivityId, importXml } = useBpmnModeler({
        withPropertiesPanel: false,
    });

    // what to deploy a fresh twin from
    const [modelIdInput, setModelIdInput] = useState("");
    // editable because twin ids don't survive a backend restart - paste the old one back in
    const [twinId, setTwinId] = useState("");
    const [agentType, setAgentType] = useState("validator");
    const [twinLog, setTwinLog] = useState([]);
    const [status, setStatus] = useState(null);
    const [busy, setBusy] = useState(false);

    // --- Governance ---
    const [deniedAgentTypesInput, setDeniedAgentTypesInput] = useState("");
    const [maxEvolutionsInput, setMaxEvolutionsInput] = useState("");
    const [policyLoaded, setPolicyLoaded] = useState(false);
    const [governanceResult, setGovernanceResult] = useState(null);
    const [governanceError, setGovernanceError] = useState(null);

    const loadModelDiagram = async (modelId) => {
        if (!modelId) return;
        try {
            const modelRes = await getModel(modelId);
            const model = modelRes.data || modelRes;
            if (model?.bpmnXml) {
                await importXml(model.bpmnXml);
            }
        } catch (err) {
            // diagram refresh failing shouldn't block the log/status update that called this
        }
    };

    const handleDeployTwin = async () => {
        const modelId = modelIdInput.trim();
        if (!modelId) {
            setStatus({ type: "err", text: "Enter the id of a saved model to deploy a twin from." });
            return;
        }
        setBusy(true);
        try {
            const res = await launchModel({ modelId });
            const twin = res.data || res;
            setTwinId(twin.id || "");
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
            await loadModelDiagram(modelId);
            setStatus({
                type: "ok",
                text: `Deployed twin ${twin.id || "?"} (original instance ${twin.originalProcessId || "?"}, twin instance ${twin.twinProcessId || "?"}).`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Deploy failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

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

    // pulls the twin's own event log AND its underlying model's diagram back in - pasting a twin
    // id you already have is otherwise a dead end with nothing on the canvas to select
    const handleRefreshTwin = async () => {
        const t = twinId.trim();
        if (!t) return;
        setBusy(true);
        try {
            const res = await getTwin(t);
            const twin = res.data || res;
            setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
            if (twin.modelId) {
                setModelIdInput(twin.modelId);
                await loadModelDiagram(twin.modelId);
            }
        } catch (err) {
            // log/diagram refresh failing shouldn't wipe whatever's already showing
        } finally {
            setBusy(false);
        }
    };

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
                    className="bpmn-load-id"
                    value={modelIdInput}
                    onChange={(e) => setModelIdInput(e.target.value)}
                    placeholder="Model id to deploy a twin from"
                />
                <Button size="sm" variant="primary" onClick={handleDeployTwin} disabled={busy}>
                    Deploy + Twin
                </Button>
                <div className="spacer" />
                {status && <span className={`bpmn-status ${statusClass}`}>{status.text}</span>}
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

export default EvolvePage;
