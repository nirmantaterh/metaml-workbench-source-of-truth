import React, { useCallback, useEffect, useState } from "react";
import { Button, Form } from "react-bootstrap";
import { Link } from "react-router-dom";

import { WorkbenchRoutes } from "../../routes";
import useBpmnModeler from "../../components/bpmn/useBpmnModeler";
import "../../components/bpmn/BpmnEditor.css";
import "./EvolvePage.css";

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

// REQUIRE_APPROVAL is approved:false but shows amber; governanceDecision is null for real denials.
const decisionStatus = (verb, decision) => {
    const reason = decision.reason || "no reason given";
    if (decision.governanceDecision === "REQUIRE_APPROVAL") {
        return {
            type: "warn",
            text: `${verb} needs approval before it can run: ${reason}`,
            link: { to: WorkbenchRoutes.GovernanceApprovals.path, label: "Go to Approvals" },
        };
    }
    return { type: "err", text: `${verb} blocked: ${reason}` };
};

const EvolvePage = () => {
    const { canvasRef, selectedActivityId, importXml } = useBpmnModeler({
        withPropertiesPanel: false,
    });

    const [modelIdInput, setModelIdInput] = useState("");
    // editable: twin ids don't survive a backend restart
    const [twinId, setTwinId] = useState("");
    const [agentType, setAgentType] = useState("validator");
    const [twinLog, setTwinLog] = useState([]);
    // drives Connect vs Evolve/Bridge context switching
    const [activityLinks, setActivityLinks] = useState([]);
    const [status, setStatus] = useState(null);
    const [busy, setBusy] = useState(false);
    const [sidebarTab, setSidebarTab] = useState("log");
    const [sidebarOpen, setSidebarOpen] = useState(true);

    const absorbTwin = (twin) => {
        setTwinLog(Array.isArray(twin.eventLog) ? twin.eventLog : []);
        setActivityLinks(Array.isArray(twin.activityLinks) ? twin.activityLinks : []);
    };

    const [deniedAgentTypesInput, setDeniedAgentTypesInput] = useState("");
    const [maxEvolutionsInput, setMaxEvolutionsInput] = useState("");
    const [policyLoaded, setPolicyLoaded] = useState(false);
    const [governanceResult, setGovernanceResult] = useState(null);
    const [governanceError, setGovernanceError] = useState(null);

    // returns false on 404; twins outlive their model so a 404 is plausible
    const loadModelDiagram = async (modelId) => {
        if (!modelId) return true;
        try {
            const modelRes = await getModel(modelId);
            const model = modelRes.data || modelRes;
            if (model?.bpmnXml) {
                await importXml(model.bpmnXml);
            }
            return true;
        } catch (err) {
            if (err.response?.status === 404) {
                return false;
            }
            return true;
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
            absorbTwin(twin);
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
            absorbTwin(res.data || res);
        } catch (err) {
            // ignore
        }
    };

    // also reloads the diagram so pasting an existing twin id isn't a dead end
    const handleRefreshTwin = async () => {
        const t = twinId.trim();
        if (!t) return;
        setBusy(true);
        try {
            const res = await getTwin(t);
            const twin = res.data || res;
            absorbTwin(twin);
            if (twin.modelId) {
                const modelStillExists = await loadModelDiagram(twin.modelId);
                if (modelStillExists) {
                    setModelIdInput(twin.modelId);
                } else {
                    // don't pre-fill: deleted model, twin still works off Camunda state
                    setModelIdInput("");
                    setStatus({
                        type: "info",
                        text: `Twin ${t} is still running, but the model it was created from `
                            + `(${twin.modelId}) has been deleted, so its diagram can't be shown. `
                            + `Evolve and Bridge still work on this twin.`,
                    });
                }
            }
        } catch (err) {
            // ignore
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
            absorbTwin(updated);
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
            const decision = res.data || res;
            // evolve returns the decision, not the twin; fetch log separately
            await refreshTwinLog(twin);
            if (decision.approved) {
                setStatus({
                    type: "ok",
                    text: `Evolved "${selectedActivityId}" with ${type}: approved, agent ${decision.agentName || "(unnamed)"}.`,
                });
            } else {
                setStatus(decisionStatus("Evolve", decision));
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
                setStatus(decisionStatus("Bridge", decision));
            }
        } catch (err) {
            setStatus({ type: "err", text: "Bridge failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // advances the original so the next activity becomes reachable; ignores canvas selection
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

    useEffect(() => {
        handleViewPolicy();
    }, [handleViewPolicy]);

    // "warn" maps to amber via local class; Bootstrap's text-warning is too pale on this background
    const statusClass =
        status?.type === "err"
            ? "text-danger"
            : status?.type === "ok"
            ? "text-success"
            : status?.type === "warn"
            ? "evolve-status-warn"
            : "text-muted";

    const selectedIsConnected =
        !!selectedActivityId &&
        activityLinks.some((link) => link.originalActivityId === selectedActivityId);

    const hasTwin = !!twinId.trim();

    const openPanel = (tab) => {
        // same-tab click closes the panel, returning full width to the canvas
        if (sidebarOpen && sidebarTab === tab) {
            setSidebarOpen(false);
            return;
        }
        setSidebarTab(tab);
        setSidebarOpen(true);
    };

    return (
        <div className="bpmn-editor">
            <div className="evolve-bar">
                <div className="evolve-bar-row">
                    <span className="evolve-label">Model</span>
                    <Form.Control
                        size="sm"
                        className="evolve-model-id"
                        value={modelIdInput}
                        onChange={(e) => setModelIdInput(e.target.value)}
                        placeholder="Model id"
                    />
                    <Button size="sm" variant="primary" onClick={handleDeployTwin} disabled={busy}>
                        Deploy + Twin
                    </Button>

                    <span className="evolve-divider" />

                    <span className="evolve-label">Twin</span>
                    <Form.Control
                        size="sm"
                        className="evolve-twin-id"
                        value={twinId}
                        onChange={(e) => setTwinId(e.target.value)}
                        placeholder="Deploy to get a twin id"
                    />
                    <Button
                        size="sm"
                        variant="outline-secondary"
                        onClick={handleRefreshTwin}
                        disabled={busy || !hasTwin}
                        title="Reload this twin's event log and its model's diagram"
                    >
                        Refresh
                    </Button>
                    {/* acts on original instance, not selected activity */}
                    <Button
                        size="sm"
                        variant="outline-success"
                        onClick={handleCompleteTasks}
                        disabled={busy || !hasTwin}
                        title="Complete open user tasks on the original process instance"
                    >
                        Complete task(s)
                    </Button>

                    <span className="evolve-divider" />

                    <span className="evolve-label">Activity</span>
                    {selectedActivityId ? (
                        <span className="evolve-chip evolve-chip-activity" title={selectedActivityId}>
                            {selectedActivityId}
                        </span>
                    ) : (
                        <span className="evolve-chip evolve-chip-muted">Select one on the canvas</span>
                    )}

                    <span className="evolve-label">Agent</span>
                    <Form.Control
                        size="sm"
                        className="evolve-agent"
                        value={agentType}
                        onChange={(e) => setAgentType(e.target.value)}
                        placeholder="Agent type"
                    />

                    {/* connected activity shows Evolve/Bridge; unconnected shows Connect */}
                    {selectedIsConnected ? (
                        <>
                            <span className="evolve-chip evolve-chip-connected">
                                <span className="evolve-dot" />
                                Connected
                            </span>
                            <Button
                                size="sm"
                                variant="outline-primary"
                                onClick={handleEvolve}
                                disabled={busy || !hasTwin || !selectedActivityId || !agentType.trim()}
                            >
                                Evolve
                            </Button>
                            <Button
                                size="sm"
                                variant="outline-primary"
                                onClick={handleBridge}
                                disabled={busy || !hasTwin || !selectedActivityId}
                            >
                                Bridge
                            </Button>
                        </>
                    ) : (
                        <Button
                            size="sm"
                            variant="outline-secondary"
                            onClick={handleConnect}
                            disabled={busy || !hasTwin || !selectedActivityId}
                        >
                            Connect
                        </Button>
                    )}

                    <span className="evolve-spacer" />

                    <Button
                        size="sm"
                        variant="outline-secondary"
                        className={`evolve-panel-toggle${sidebarOpen && sidebarTab === "log" ? " active" : ""}`}
                        onClick={() => openPanel("log")}
                    >
                        Event log
                    </Button>
                    <Button
                        size="sm"
                        variant="outline-secondary"
                        className={`evolve-panel-toggle${sidebarOpen && sidebarTab === "governance" ? " active" : ""}`}
                        onClick={() => openPanel("governance")}
                    >
                        Governance
                    </Button>
                </div>

                {(status || !hasTwin || !selectedActivityId) && (
                    <div className={`evolve-bar-status ${status ? statusClass : "text-muted"}`}>
                        {status
                            ? status.text
                            : !hasTwin
                            ? "Deploy + Twin first, or paste an existing twin id."
                            : "Select an activity on the canvas to connect, evolve or bridge it."}
                        {status?.link && (
                            <Link className="evolve-status-link" to={status.link.to}>
                                {status.link.label}
                            </Link>
                        )}
                    </div>
                )}
            </div>

            <div className="bpmn-main">
                <div className="bpmn-canvas" ref={canvasRef} />
                {sidebarOpen && (
                    <div className="bpmn-sidebar evolve-sidebar">
                        <div className="evolve-tabs">
                            <button
                                type="button"
                                className={`evolve-tab${sidebarTab === "log" ? " active" : ""}`}
                                onClick={() => setSidebarTab("log")}
                            >
                                Twin Event Log
                            </button>
                            {/* platform denylist/quota, not the tenant policy layer */}
                            <button
                                type="button"
                                className={`evolve-tab${sidebarTab === "governance" ? " active" : ""}`}
                                onClick={() => setSidebarTab("governance")}
                            >
                                Governance
                            </button>
                        </div>

                        <div className="evolve-tab-body">
                            {sidebarTab === "log" ? (
                                <>
                                    <div className="evolve-panel-head">
                                        <span className="bpmn-sidebar-eyebrow mb-0">Twin activity</span>
                                        <Button
                                            size="sm"
                                            variant="outline-secondary"
                                            className="py-0"
                                            onClick={handleRefreshTwin}
                                            disabled={busy || !hasTwin}
                                        >
                                            Refresh
                                        </Button>
                                    </div>
                                    <div className="bpmn-log-section">
                                        {!hasTwin ? (
                                            <p className="text-muted small mb-0">
                                                Deploy a model to start a twin and see its event log here.
                                            </p>
                                        ) : twinLog.length === 0 ? (
                                            <p className="text-muted small mb-0">
                                                No events logged for this twin yet.
                                            </p>
                                        ) : (
                                            <ol className="bpmn-log-list">
                                                {twinLog.map((entry, idx) => (
                                                    <li key={idx}>{entry}</li>
                                                ))}
                                            </ol>
                                        )}
                                    </div>
                                </>
                            ) : (
                                <>
                                    <div className="evolve-gov-form">
                                        <span className="bpmn-sidebar-eyebrow mb-0">Platform policy</span>
                                        <Form.Control
                                            size="sm"
                                            value={deniedAgentTypesInput}
                                            onChange={(e) => setDeniedAgentTypesInput(e.target.value)}
                                            placeholder="Denied agent types (comma-separated)"
                                        />
                                        <Form.Control
                                            size="sm"
                                            type="number"
                                            min="0"
                                            value={maxEvolutionsInput}
                                            onChange={(e) => setMaxEvolutionsInput(e.target.value)}
                                            placeholder="Max evolutions/twin"
                                        />
                                        <div className="evolve-gov-actions">
                                            <Button
                                                size="sm"
                                                variant="outline-secondary"
                                                onClick={handleViewPolicy}
                                                disabled={busy}
                                            >
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
                                                disabled={busy || !hasTwin}
                                            >
                                                Usage for this twin
                                            </Button>
                                        </div>
                                    </div>
                                    <div className="bpmn-log-section">
                                        {governanceError && (
                                            <p className="text-danger small mb-0">{governanceError}</p>
                                        )}
                                        {!governanceError && governanceResult && (
                                            <>
                                                <div className="small text-muted mb-1">
                                                    {governanceResult.label}
                                                </div>
                                                <pre className="bpmn-json mb-0">
                                                    {JSON.stringify(governanceResult.body, null, 2)}
                                                </pre>
                                            </>
                                        )}
                                        {!governanceError && !governanceResult && (
                                            <p className="text-muted small mb-0">
                                                Click "View policy" or "Usage for this twin" above.
                                            </p>
                                        )}
                                    </div>
                                </>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
};

export default EvolvePage;
