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
    // present only on the Edit Existing Project path (/wb/model/:id) - absent on Create
    // (/wb/model/new), which is exactly what tells this page whether to auto-load or start blank
    const { id: routeModelId } = useParams();

    const { canvasRef, propertiesPanelRef, modelerRef, selected, importXml, currentXml } = useBpmnModeler();

    // hidden file input behind the "Open BPMN file" button - a bare <input type="file"> can't be
    // styled to match the rest of the toolbar, so the button drives it
    const bpmnFileInputRef = useRef(null);

    const [modelName, setModelName] = useState("New Process");
    // Tenant ownership (Phase 1, see the governance audit): optional, caller-supplied, not
    // authentication - same trust level and same "" = unowned convention GovernancePoliciesPage
    // already uses for its own tenant selector, reused here rather than duplicated. Save always
    // creates a brand-new ProcessModel (the backend never overwrites - see handleSave's own
    // comment), so picking a tenant here is never reassigning an existing model's owner, only
    // choosing the owner of whichever model this Save is about to create.
    const [tenantId, setTenantId] = useState("");
    const [tenants, setTenants] = useState([]);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await listTenants();
                if (!cancelled) setTenants(res.data || res || []);
            } catch (err) {
                // an empty selector still leaves models fully creatable as unowned - the same
                // "not required" behavior as if the list had loaded and nothing was picked
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);
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
    // Phase 2C: purely a "is the panel open" toggle - the panel itself renders straight off
    // workflowState below, same as WorkflowProgress does, so there's nothing else to track here
    const [detailsOpen, setDetailsOpen] = useState(false);

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
                // display only - honestly reflects this model's real ownership (blank for a
                // legacy tenantId=null model, exactly like "" already means "unowned" everywhere
                // else). Whatever ends up selected here only affects the NEXT model Save creates,
                // never this loaded one - see the tenantId state's own comment on why that's safe.
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

    // Opening a local .bpmn file. Deliberately client-side only: the file is read in the browser
    // and handed to the modeler this page already owns, so nothing reaches the backend until the
    // user presses Save exactly as they would for a diagram they drew by hand. There is no upload
    // endpoint and no server-side temp file, and importing does NOT save, deploy, generate, launch,
    // create a twin or assign a tenant - Save stays the one persistence boundary.
    //
    // A successful import means only "this XML loaded into bpmn-js". It is NOT a statement that
    // MetaML accepts the model: the real BPMN/Camunda validation still happens in saveProcessModel
    // (single process element, isExecutable, deployable), and that is left untouched on purpose -
    // an imported file that Camunda rejects must still fail at Save, the same as any other.
    const handleOpenBpmnFile = async (event) => {
        const file = event.target.files && event.target.files[0];
        // re-selecting the SAME file after a failed import fires no change event unless the input
        // is cleared, which would strand the user on a file they can see but not retry
        event.target.value = "";
        if (!file) {
            return;
        }
        setBusy(true);
        try {
            const xml = await file.text();
            await importXml(xml);
            // savedModelId/projectId are deliberately left alone: importing a file over a loaded
            // model does not make the file that model. Save mints a new id anyway (the backend
            // never overwrites), so the previously loaded model stays exactly as it was on disk.
            setStatus({
                type: "ok",
                text: `Opened "${file.name}". Nothing is saved yet - review it, then press Save.`,
            });
        } catch (err) {
            // covers both a file that could not be read and XML that bpmn-js refused to import.
            // The canvas keeps whatever it had before, so the user can simply pick another file.
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
            // "" (nothing selected) must become null, not be sent as a literal empty-string
            // tenantId - runEvolution only skips tenant governance when tenantId is exactly null
            // (twin.getTenantId() != null), so an empty string here would enter enforceTenantPolicy,
            // fail to find a tenant record, and DENY every evolution on the model instead of
            // leaving it honestly ungoverned.
            const res = await saveModel({ name: modelName, bpmnXml, tenantId: tenantId || null });
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
    // editor now instead of only existing as a bare API. This used to call generateDelegates first
    // and then generateProject, which looked harmless because generateDelegates' result was thrown
    // away - the project is the thing Launch actually needs. It wasn't harmless: generateDelegates
    // records no workflow state, so a generation failure raised there aborted the click before
    // generateProject ran, and nothing ever wrote the FAILED stage that carries bpmnElementId. The
    // banner still showed the message, but the breadcrumb sat on "Pending" and "Go to error" never
    // appeared - the one case where knowing which element broke matters most. generateProject
    // regenerates the delegates itself anyway (and against the right package, see
    // doGenerateSpringBootProject), so the preview call was only ever duplicating that work outside
    // the recorded path.
    const handleGenerate = async () => {
        if (!savedModelId) {
            setStatus({ type: "err", text: "Save the model before generating a project." });
            return;
        }
        // busy flipping true is what starts the polling effect above (see its own comment) - no
        // separate manual refresh needed here anymore
        setBusy(true);
        try {
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

    // Phase 3C: uses the SAME modeler instance already driving the canvas above (modelerRef, from
    // useBpmnModeler) - no second modeler, no reload of the BPMN, no navigation. bpmnElementId
    // only ever refers to this page's own currently-loaded model, since it was computed from this
    // model's own BPMN at generate time (see GeneratedDelegate/DelegateClassGenerator) - there's
    // no cross-model case to handle here.
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
            // an element that exists but can't be selected/scrolled to for some modeler-internal
            // reason is the same "couldn't get there" outcome as not finding it at all
            return false;
        }
    };

    return (
        <div className="bpmn-editor">
            <div className="bpmn-toolbar">
                {/* row 1: user actions - naming/saving/generating/launching the model */}
                <div className="bpmn-toolbar-row bpmn-toolbar-actions">
                    {/* Loading a local .bpmn into this same editor. Sits before the name/tenant/Save
                        cluster because it is a canvas action, not a persistence one - see
                        handleOpenBpmnFile. */}
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
                    {/* Tenant ownership - a plain selector, not authentication (no login exists in
                        this system yet). Optional: leaving it on "No tenant" saves an unowned
                        model exactly like every model saved before tenancy existed - governance
                        does not require an owner, it just has nothing to enforce without one. */}
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

                {/* row 2: system status - a read-only projection of the backend's own workflow
                    state, never a set of buttons in its own right (see WorkflowProgress) */}
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
