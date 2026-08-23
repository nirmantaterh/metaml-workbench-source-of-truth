import React, { useEffect, useRef, useState } from "react";
import { Button, Form } from "react-bootstrap";
import { useLocation, useNavigate, useParams } from "react-router-dom";

import DataPanel from "../../components/bpmn/DataPanel";
import useBpmnModeler from "../../components/bpmn/useBpmnModeler";
import "../../components/bpmn/BpmnEditor.css";
import WorkflowProgress from "../../components/workbench/WorkflowProgress";
import WorkflowDetailsPanel from "../../components/workbench/WorkflowDetailsPanel";

import {
    saveModel,
    saveModelWithAuthoredTwin,
    getModel,
    getWorkflowState,
    listTenants,
} from "../../services/workbench/WorkbenchService";
import { listProjects } from "../../services/workbench/ProjectService";
import { WorkbenchRoutes } from "../../routes";

// Model is only the first third of Model -> Generate -> Launch: this page just edits and saves a
// BPMN. Generate and Launch both moved out to their own Transmute pickers (see
// GenerateProjectListPage / LaunchProjectListPage) - each needs to list every saved process
// across every project before acting on one, which this single-model editor has no reason to do.
const ModelPage = () => {
    const { id: routeModelId } = useParams();
    const location = useLocation();
    const navigate = useNavigate();

    const { canvasRef, propertiesPanelRef, modelerRef, selected, importXml, currentXml } = useBpmnModeler();

    const bpmnFileInputRef = useRef(null);
    const twinFileInputRef = useRef(null);

    const [modelName, setModelName] = useState("New Process");
    // Raw XML of an independently authored Twin BPMN, provided alongside the Main diagram in the
    // canvas above - not rendered in the canvas itself (the modeler only ever shows one process),
    // just carried alongside it and sent together on Save (see saveModelWithAuthoredTwin). null
    // means "no Twin attached" -> Save falls back to the original single-BPMN path.
    const [twinBpmnXml, setTwinBpmnXml] = useState(null);
    const [twinFileName, setTwinFileName] = useState(null);
    // caller-supplied ownership, not auth; "" = unowned
    const [tenantId, setTenantId] = useState("");
    const [tenants, setTenants] = useState([]);
    const [projects, setProjects] = useState([]);
    const [selectedProjectId, setSelectedProjectId] = useState(location.state?.projectId || "");

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await listTenants();
                if (!cancelled) setTenants(res.data || res || []);
            } catch (err) {
                // ignore: empty tenant list still allows saving unowned models
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const response = await listProjects();
                if (!cancelled) setProjects(response.data || response || []);
            } catch (err) {
                if (!cancelled) setStatus({ type: "err", text: "Could not load projects: " + (err.response?.data?.message || err.message) });
            }
        })();
        return () => { cancelled = true; };
    }, []);
    const [workflowState, setWorkflowState] = useState(null);

    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);
    const [detailsOpen, setDetailsOpen] = useState(false);

    const refreshWorkflowState = async (modelId) => {
        if (!modelId) return;
        try {
            const res = await getWorkflowState(modelId);
            setWorkflowState(res.data || res);
        } catch (err) {
            // ignore: workflow breadcrumb failing shouldn't block the rest of the page
        }
    };

    useEffect(() => {
        if (!routeModelId) return;
        let cancelled = false;
        (async () => {
            setBusy(true);
            try {
                const res = await getModel(routeModelId);
                const model = res.data || res;
                if (cancelled) return;
                if (!model || !model.bpmnXml) {
                    throw new Error("Model has no BPMN XML");
                }
                await importXml(model.bpmnXml);
                setModelName(model.name || "Untitled");
                setTenantId(model.tenantId || "");
                // Restore a previously-attached Twin so re-saving (e.g. after editing Main) keeps
                // persisting both, rather than silently dropping back to single-BPMN.
                if (model.authoredTwinBpmnXml) {
                    setTwinBpmnXml(model.authoredTwinBpmnXml);
                    setTwinFileName("(restored from saved model)");
                } else {
                    setTwinBpmnXml(null);
                    setTwinFileName(null);
                }
                setStatus({ type: "ok", text: `Loaded "${model.name || routeModelId}".` });
                await refreshWorkflowState(model.id || routeModelId);
            } catch (err) {
                if (!cancelled) {
                    setStatus({ type: "err", text: "Load failed: " + (err.response?.data?.message || err.message) });
                }
            } finally {
                if (!cancelled) setBusy(false);
            }
        })();
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [routeModelId]);

    const handleOpenBpmnFile = async (event) => {
        const file = event.target.files && event.target.files[0];
        // clear so re-selecting the same file after a failed import still fires onChange
        event.target.value = "";
        if (!file) {
            return;
        }
        setBusy(true);
        try {
            const xml = await file.text();
            await importXml(xml);
            setStatus({
                type: "ok",
                text: `Opened "${file.name}". Nothing is saved yet - review it, then press Save.`,
            });
        } catch (err) {
            setStatus({
                type: "err",
                text: `Could not open "${file.name}": ${err.message || err}. The diagram is unchanged.`,
            });
        } finally {
            setBusy(false);
        }
    };

    const handleAttachTwinFile = async (event) => {
        const file = event.target.files && event.target.files[0];
        event.target.value = "";
        if (!file) {
            return;
        }
        try {
            const xml = await file.text();
            setTwinBpmnXml(xml);
            setTwinFileName(file.name);
            setStatus({
                type: "ok",
                text: `Attached Twin "${file.name}". Nothing is saved yet - press Save to persist Main + Twin together.`,
            });
        } catch (err) {
            setStatus({ type: "err", text: `Could not read Twin file "${file.name}": ${err.message || err}.` });
        }
    };

    const handleClearTwin = () => {
        setTwinBpmnXml(null);
        setTwinFileName(null);
        setStatus({ type: "info", text: "Twin removed. The next Save persists Main only." });
    };

    const handleSave = async () => {
        if (!selectedProjectId) {
            setStatus({ type: "err", text: "Select a project before saving the process model." });
            return;
        }
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            // "" must become null; backend skips tenant governance only on strict null, not empty string
            const res = twinBpmnXml
                ? await saveModelWithAuthoredTwin({ name: modelName, bpmnXml, twinBpmnXml, tenantId: tenantId || null, projectId: Number(selectedProjectId) })
                : await saveModel({ name: modelName, bpmnXml, tenantId: tenantId || null, projectId: Number(selectedProjectId) });
            const saved = res.data || res;
            setStatus({
                type: "ok",
                text: twinBpmnXml
                    ? `Saved Main + Twin "${saved.name || modelName}" (id ${saved.id ?? "?"}).`
                    : `Saved model "${saved.name || modelName}" (id ${saved.id ?? "?"}).`,
            });
            await refreshWorkflowState(saved.id);
        } catch (err) {
            setStatus({ type: "err", text: "Save failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const statusClass =
        status?.type === "err" ? "text-danger" : status?.type === "ok" ? "text-success" : "text-muted";

    const handleGoToError = (bpmnElementId) => {
        const modeler = modelerRef.current;
        if (!modeler || !bpmnElementId) return false;
        try {
            const element = modeler.get("elementRegistry").get(bpmnElementId);
            if (!element) return false;
            modeler.get("selection").select(element);
            modeler.get("canvas").scrollToElement(element);
            return true;
        } catch (err) {
            return false;
        }
    };

    return (
        <div className="bpmn-editor">
            <div className="bpmn-toolbar">
                {/* Own row, above the buttons - a status message never shares a line with the
                    controls that produced it, so a long message never pushes a button off screen
                    or the other way around. */}
                {status && (
                    <div className="bpmn-toolbar-row bpmn-toolbar-message">
                        <span className={`bpmn-status ${statusClass}`}>{status.text}</span>
                    </div>
                )}
                <div className="bpmn-toolbar-row bpmn-toolbar-actions">
                    <input
                        ref={bpmnFileInputRef}
                        type="file"
                        accept=".bpmn,.xml"
                        style={{ display: "none" }}
                        onChange={handleOpenBpmnFile}
                    />
                    <Button
                        size="sm"
                        variant="outline-secondary"
                        onClick={() => bpmnFileInputRef.current && bpmnFileInputRef.current.click()}
                        disabled={busy}
                        title="Load a local .bpmn file into the editor. Nothing is saved until you press Save."
                    >
                        Open BPMN file
                    </Button>
                    <input
                        ref={twinFileInputRef}
                        type="file"
                        accept=".bpmn,.xml"
                        style={{ display: "none" }}
                        onChange={handleAttachTwinFile}
                    />
                    {twinFileName ? (
                        <span className="bpmn-twin-badge" title={twinFileName}>
                            Twin: {twinFileName}
                            <button
                                type="button"
                                className="bpmn-twin-badge-clear"
                                onClick={handleClearTwin}
                                disabled={busy}
                                aria-label="Remove attached Twin"
                                title="Remove attached Twin (next Save persists Main only)"
                            >
                                &times;
                            </button>
                        </span>
                    ) : (
                        <Button
                            size="sm"
                            variant="outline-secondary"
                            onClick={() => twinFileInputRef.current && twinFileInputRef.current.click()}
                            disabled={busy}
                            title="Attach an independently authored Twin BPMN file. Sent together with Main on the next Save."
                        >
                            Attach Twin BPMN
                        </Button>
                    )}
                    <Form.Control
                        size="sm"
                        className="bpmn-model-name"
                        value={modelName}
                        onChange={(e) => setModelName(e.target.value)}
                        placeholder="Model name"
                    />
                    <Form.Select
                        size="sm"
                        className="bpmn-model-project"
                        style={{ maxWidth: 220 }}
                        value={selectedProjectId}
                        onChange={(e) => setSelectedProjectId(e.target.value)}
                        aria-label="Project"
                    >
                        <option value="">Select project</option>
                        {projects.map((project) => (
                            <option key={project.id} value={project.id}>
                                {project.displayName || project.name}
                            </option>
                        ))}
                    </Form.Select>
                    <span
                        className="text-muted small bpmn-model-tenant-label"
                        title="Tenant selection is caller-supplied ownership metadata, not a login"
                    >
                        Acting as tenant (not authenticated):
                    </span>
                    <Form.Select
                        size="sm"
                        className="bpmn-model-tenant"
                        style={{ maxWidth: 220 }}
                        value={tenantId}
                        onChange={(e) => setTenantId(e.target.value)}
                    >
                        <option value="">No tenant (unowned)</option>
                        {tenants.map((t) => (
                            <option key={t.id} value={t.id}>
                                {t.name} ({t.id})
                            </option>
                        ))}
                    </Form.Select>
                    <div className="spacer" />
                    {selectedProjectId && (
                        <Button
                            size="sm"
                            variant="outline-secondary"
                            onClick={() => navigate(WorkbenchRoutes.ProjectProcesses.path.replace(":projectId", selectedProjectId))}
                            disabled={busy}
                        >
                            Back to project processes
                        </Button>
                    )}
                    <Button size="sm" variant="outline-primary" onClick={handleSave} disabled={busy}>
                        Save
                    </Button>
                </div>

                <div className="bpmn-toolbar-row bpmn-toolbar-status">
                    <WorkflowProgress currentStage={workflowState?.currentStage} stages={workflowState?.stages} />
                    <div className="spacer" />
                    <div className="workflow-view-details-anchor">
                        <button
                            type="button"
                            className="workflow-view-details"
                            onClick={() => setDetailsOpen((open) => !open)}
                            aria-expanded={detailsOpen}
                            disabled={!workflowState}
                            title={!workflowState ? "Save the model first" : undefined}
                        >
                            View details {detailsOpen ? "▴" : "▾"}
                        </button>
                        {detailsOpen && workflowState && (
                            <WorkflowDetailsPanel
                                workflowState={workflowState}
                                onClose={() => setDetailsOpen(false)}
                                onGoToError={handleGoToError}
                            />
                        )}
                    </div>
                </div>
            </div>

            <div className="bpmn-main">
                <div className="bpmn-canvas" ref={canvasRef} />
                <div className="bpmn-sidebar">
                    <div className="bpmn-properties-panel" ref={propertiesPanelRef} />

                    <div className="bpmn-sidebar-header">Data</div>
                    <DataPanel modeler={modelerRef.current} element={selected} />
                </div>
            </div>
        </div>
    );
};

export default ModelPage;
