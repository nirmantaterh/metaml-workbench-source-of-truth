import React, { useEffect, useRef, useState } from "react";
import { Button, Form } from "react-bootstrap";
import { useParams } from "react-router-dom";

import DataPanel from "../../components/bpmn/DataPanel";
import useBpmnModeler from "../../components/bpmn/useBpmnModeler";
import "../../components/bpmn/BpmnEditor.css";
import WorkflowProgress from "../../components/workbench/WorkflowProgress";
import WorkflowDetailsPanel from "../../components/workbench/WorkflowDetailsPanel";

import {
    saveModel,
    getModel,
    generateProject,
    launchProject,
    getWorkflowState,
    listTenants,
} from "../../services/workbench/WorkbenchService";

const ModelPage = () => {
    const { id: routeModelId } = useParams();

    const { canvasRef, propertiesPanelRef, modelerRef, selected, importXml, currentXml } = useBpmnModeler();

    const bpmnFileInputRef = useRef(null);

    const [modelName, setModelName] = useState("New Process");
    // caller-supplied ownership, not auth; "" = unowned
    const [tenantId, setTenantId] = useState("");
    const [tenants, setTenants] = useState([]);

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
    const [savedModelId, setSavedModelId] = useState(null);
    // restored from workflow state on load so Launch stays usable after a reload
    const [projectId, setProjectId] = useState(null);
    const [workflowState, setWorkflowState] = useState(null);

    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);
    const [detailsOpen, setDetailsOpen] = useState(false);

    const refreshWorkflowState = async (modelId) => {
        if (!modelId) return;
        try {
            const res = await getWorkflowState(modelId);
            const state = res.data || res;
            setWorkflowState(state);
            // restore projectId so Launch stays available after a reload
            const generateStage = state.stages?.GENERATE;
            if (generateStage?.status === "COMPLETED" && generateStage.detail) {
                setProjectId(generateStage.detail);
            }
        } catch (err) {
            // ignore: workflow breadcrumb failing shouldn't block the rest of the page
        }
    };

    // boolean not the full object; prevents effect restart on every workflowState tick
    const currentStageInProgress =
        workflowState?.stages?.[workflowState?.currentStage]?.status === "IN_PROGRESS";

    // busy starts polling immediately; waiting for IN_PROGRESS alone misses the gap before the first poll
    const shouldPoll = (busy || currentStageInProgress) && Boolean(savedModelId);

    useEffect(() => {
        if (!shouldPoll) return undefined;
        let cancelled = false;
        const modelId = savedModelId;
        refreshWorkflowState(modelId);
        const interval = setInterval(() => {
            if (cancelled) return;
            refreshWorkflowState(modelId);
        }, 1000);
        return () => {
            cancelled = true;
            clearInterval(interval);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [shouldPoll, savedModelId]);

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
                setSavedModelId(model.id || routeModelId);
                setTenantId(model.tenantId || "");
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

    const handleSave = async () => {
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            // "" must become null; backend skips tenant governance only on strict null, not empty string
            const res = await saveModel({ name: modelName, bpmnXml, tenantId: tenantId || null });
            const saved = res.data || res;
            setSavedModelId(saved.id || null);
            // each save is a new entity; the previous project/launch no longer applies
            setProjectId(null);
            setStatus({ type: "ok", text: `Saved model "${saved.name || modelName}" (id ${saved.id ?? "?"}).` });
            await refreshWorkflowState(saved.id);
        } catch (err) {
            setStatus({ type: "err", text: "Save failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // Shared by the manual Launch button and the automatic post-generation launch below. Reports
    // its own success/failure so a launch failure is never mistaken for a generate failure.
    const launchProjectId = async (idToLaunch) => {
        try {
            const res = await launchProject({ projectId: idToLaunch });
            const launched = res.data || res;
            setStatus({
                type: "ok",
                text: `Launched on port ${launched.port ?? "?"} (process "${launched.processKey || "?"}").`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Launch failed: " + (err.response?.data?.message || err.message) });
        }
    };

    const handleGenerate = async () => {
        if (!savedModelId) {
            setStatus({ type: "err", text: "Save the model before generating a Target Harness Platform." });
            return;
        }
        setBusy(true);
        try {
            const res = await generateProject({ modelId: savedModelId });
            const project = res.data || res;
            const newProjectId = project.projectId || null;
            setProjectId(newProjectId);
            // Automatic launch after generation (explicit requirement): a successful Generate must
            // not require a separate manual Launch click. Reuses the same launch endpoint the Launch
            // button uses; the Model -> Generate -> Launch indicator still advances on its own as each
            // backend stage completes. The Launch button stays for re-launching later.
            if (newProjectId) {
                setStatus({
                    type: "info",
                    text: `Generated Target Harness Platform for process "${project.processKey || "?"}" (id ${newProjectId}) - launching...`,
                });
                await launchProjectId(newProjectId);
            } else {
                setStatus({ type: "err", text: "Generation returned no project id to launch." });
            }
        } catch (err) {
            setStatus({ type: "err", text: "Generate failed: " + (err.response?.data?.message || err.message) });
        } finally {
            await refreshWorkflowState(savedModelId);
            setBusy(false);
        }
    };

    const handleLaunch = async () => {
        if (!projectId) {
            setStatus({ type: "err", text: "Generate a project before launching it." });
            return;
        }
        setBusy(true);
        await launchProjectId(projectId);
        await refreshWorkflowState(savedModelId);
        setBusy(false);
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
                    <Form.Control
                        size="sm"
                        className="bpmn-model-name"
                        value={modelName}
                        onChange={(e) => setModelName(e.target.value)}
                        placeholder="Model name"
                    />
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
                    {status && <span className={`bpmn-status ${statusClass}`}>{status.text}</span>}
                    <Button size="sm" variant="outline-primary" onClick={handleSave} disabled={busy}>
                        Save
                    </Button>
                    <Button
                        size="sm"
                        variant="outline-primary"
                        onClick={handleGenerate}
                        disabled={busy || !savedModelId}
                        title={!savedModelId ? "Save the model first" : undefined}
                    >
                        Generate
                    </Button>
                    <Button
                        size="sm"
                        variant="primary"
                        onClick={handleLaunch}
                        disabled={busy || !projectId}
                        title={!projectId ? "Generate a project first" : undefined}
                    >
                        Launch
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
