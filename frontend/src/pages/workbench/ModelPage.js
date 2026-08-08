import React, { useEffect, useState } from "react";
import { Button, Form } from "react-bootstrap";
import { useParams } from "react-router-dom";

import DataPanel from "../../components/bpmn/DataPanel";
import useBpmnModeler from "../../components/bpmn/useBpmnModeler";
import "../../components/bpmn/BpmnEditor.css";
import WorkflowProgress from "../../components/workbench/WorkflowProgress";

import {
    saveModel,
    getModel,
    generateDelegates,
    generateProject,
    launchProject,
} from "../../services/workbench/WorkbenchService";

const ModelPage = () => {
    // present only on the Edit Existing Project path (/wb/model/:id) - absent on Create
    // (/wb/model/new), which is exactly what tells this page whether to auto-load or start blank
    const { id: routeModelId } = useParams();

    const { canvasRef, propertiesPanelRef, modelerRef, selected, importXml, currentXml } = useBpmnModeler();

    const [modelName, setModelName] = useState("New Process");
    // each save is its own version - the backend won't overwrite an existing model, so this only
    // ever gets set FROM a successful save or an existing model load, never sent back as a request
    const [savedModelId, setSavedModelId] = useState(null);
    const [projectId, setProjectId] = useState(null);
    const [launchedPort, setLaunchedPort] = useState(null);

    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);

    // Edit Existing Project lands here with a real id already in the URL - load it straight away
    // instead of making the user paste it into a box, which is the whole point of the picker page
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
                setStatus({ type: "ok", text: `Loaded "${model.name || routeModelId}".` });
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

    // any edit after a save/generate/launch means the saved version on the server is no longer
    // what's on the canvas - re-saving makes a NEW model id (see handleSave), so Generate/Launch
    // against the stale one would be editing a project that no longer matches what's shown here
    const resetDownstreamPhases = () => {
        setProjectId(null);
        setLaunchedPort(null);
    };

    const handleSave = async () => {
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            const res = await saveModel({ name: modelName, bpmnXml });
            const saved = res.data || res;
            setSavedModelId(saved.id || null);
            resetDownstreamPhases();
            setStatus({ type: "ok", text: `Saved model "${saved.name || modelName}" (id ${saved.id ?? "?"}).` });
        } catch (err) {
            setStatus({ type: "err", text: "Save failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // New scope item 3 (BPMN Processing) + item 4 (Spring Boot Generation), reached from the
    // editor now instead of only existing as a bare API. One click does both steps that only ever
    // make sense together here - generateDelegates on its own is a preview with nothing to show
    // for it in this UI, the project is the thing Launch actually needs.
    const handleGenerate = async () => {
        if (!savedModelId) {
            setStatus({ type: "err", text: "Save the model before generating a project." });
            return;
        }
        setBusy(true);
        try {
            await generateDelegates({ modelId: savedModelId });
            const res = await generateProject({ modelId: savedModelId });
            const project = res.data || res;
            setProjectId(project.projectId || null);
            setLaunchedPort(null);
            setStatus({
                type: "ok",
                text: `Generated Spring Boot project for process "${project.processKey || "?"}" (id ${project.projectId ?? "?"}).`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Generate failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    const handleLaunch = async () => {
        if (!projectId) {
            setStatus({ type: "err", text: "Generate a project before launching it." });
            return;
        }
        setBusy(true);
        try {
            const res = await launchProject({ projectId });
            const launched = res.data || res;
            setLaunchedPort(launched.port ?? null);
            setStatus({
                type: "ok",
                text: `Launched on port ${launched.port ?? "?"} (process "${launched.processKey || "?"}").`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Launch failed: " + (err.response?.data?.message || err.message) });
        } finally {
            setBusy(false);
        }
    };

    // -1 = nothing saved yet, 0 = model saved, 1 = project generated, 2 = launched
    const currentPhase = launchedPort != null ? 2 : projectId != null ? 1 : savedModelId != null ? 0 : -1;

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
                <WorkflowProgress currentPhase={currentPhase} />
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
