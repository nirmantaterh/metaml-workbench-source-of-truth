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
    getWorkflowState,
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
    // populated either by a fresh Generate call, or restored from the real backend workflow state
    // on load - so reopening a model that was already generated makes Launch usable again without
    // re-running Generate, instead of the page pretending nothing happened just because its own
    // local variables reset on reload
    const [projectId, setProjectId] = useState(null);

    // the actual source of truth for the breadcrumb - never computed from savedModelId/projectId
    // locally, always the backend's own record of what happened to this model's pipeline (see
    // WorkbenchService.getWorkflowState / the backend's WorkflowStateTracker)
    const [workflowState, setWorkflowState] = useState(null);

    const [status, setStatus] = useState(null); // { type: 'ok'|'err'|'info', text }
    const [busy, setBusy] = useState(false);

    const refreshWorkflowState = async (modelId) => {
        if (!modelId) return;
        try {
            const res = await getWorkflowState(modelId);
            const state = res.data || res;
            setWorkflowState(state);
            // restore projectId from the real record, not just from this session's own clicks -
            // this is what makes Launch resumable after a reload or after opening a model that was
            // generated in an earlier session
            const generateStage = state.stages?.GENERATE;
            if (generateStage?.status === "COMPLETED" && generateStage.detail) {
                setProjectId(generateStage.detail);
            }
        } catch (err) {
            // breadcrumb failing to load shouldn't block anything else on the page
        }
    };

    // Phase 2A: live status while a stage is actually IN_PROGRESS, per the backend's own record -
    // not inferred from a button click or from a request still being in flight. Derived as a plain
    // boolean (not the whole workflowState object) specifically so the polling effect below only
    // restarts its interval when this flips true/false, not on every single poll tick - each poll
    // response replaces workflowState with a new object reference, and depending on that directly
    // would tear down and recreate the interval every second instead of running one continuously.
    const currentStageInProgress =
        workflowState?.stages?.[workflowState?.currentStage]?.status === "IN_PROGRESS";

    // `busy` is what actually starts polling, not currentStageInProgress alone - found this the
    // hard way testing against a real slow Launch: gating the poll purely on "we already know a
    // stage is IN_PROGRESS" is a chicken-and-egg problem, since nothing was ever polling to
    // DISCOVER that transition in the first place. A single fire-and-forget GET at click time
    // (an earlier version of this) mostly loses the race against the backend recording
    // IN_PROGRESS, leaving the breadcrumb stuck showing whatever it last knew (PENDING) for the
    // entire operation. Polling for real backend truth starts the moment the user clicks Save/
    // Generate/Launch (busy) and keeps running until both the click's own request has settled AND
    // the backend no longer reports anything IN_PROGRESS - this is still not "inferring" anything:
    // every value actually rendered still comes straight from a real GET response, busy only
    // controls how often to ask.
    const shouldPoll = (busy || currentStageInProgress) && Boolean(savedModelId);

    // No SSE/WebSocket infrastructure exists anywhere in this backend (checked before writing
    // this), so lightweight polling of the existing GET .../workflow is the correct choice here,
    // not a shortcut around a real-time mechanism that was already available.
    useEffect(() => {
        if (!shouldPoll) return undefined;
        let cancelled = false;
        const modelId = savedModelId;
        // an immediate check the moment polling starts, not just the interval's first tick a full
        // second later - this is what replaced the earlier single fire-and-forget "kick" call in
        // each handler, now centralized here instead of duplicated per action
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

    const handleSave = async () => {
        setBusy(true);
        try {
            const bpmnXml = await currentXml();
            const res = await saveModel({ name: modelName, bpmnXml });
            const saved = res.data || res;
            setSavedModelId(saved.id || null);
            // a fresh save is a NEW model id (the backend never overwrites), so any earlier
            // project/launch belonged to the OLD id - nothing to carry over here, the next
            // refreshWorkflowState call reads the new id's history, which starts empty
            setProjectId(null);
            setStatus({ type: "ok", text: `Saved model "${saved.name || modelName}" (id ${saved.id ?? "?"}).` });
            await refreshWorkflowState(saved.id);
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
        // busy flipping true is what starts the polling effect above (see its own comment) - no
        // separate manual refresh needed here anymore
        setBusy(true);
        try {
            await generateDelegates({ modelId: savedModelId });
            const res = await generateProject({ modelId: savedModelId });
            const project = res.data || res;
            setProjectId(project.projectId || null);
            setStatus({
                type: "ok",
                text: `Generated Spring Boot project for process "${project.processKey || "?"}" (id ${project.projectId ?? "?"}).`,
            });
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
        // same reasoning as handleGenerate above
        setBusy(true);
        try {
            const res = await launchProject({ projectId });
            const launched = res.data || res;
            setStatus({
                type: "ok",
                text: `Launched on port ${launched.port ?? "?"} (process "${launched.processKey || "?"}").`,
            });
        } catch (err) {
            setStatus({ type: "err", text: "Launch failed: " + (err.response?.data?.message || err.message) });
        } finally {
            await refreshWorkflowState(savedModelId);
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
                <WorkflowProgress currentStage={workflowState?.currentStage} stages={workflowState?.stages} />
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
