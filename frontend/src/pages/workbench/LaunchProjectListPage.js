import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table } from "react-bootstrap";

import { listModelSummaries, getWorkflowState, launchProject } from "../../services/workbench/WorkbenchService";
import { openCockpitUrl } from "../../components/workbench/openCockpitUrl";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

// Transmute > Launch: every process that has already been through Generate, one row each - run
// 'mvn clean install -DskipTests' then 'mvnw spring-boot:run' (see SpringBootProjectLauncher) to
// actually deploy the generated Target Platform, then immediately start a paired proxy + twin
// instance on it and open Cockpit - one click does the whole thing, no separate "now start it"
// step. A process that hasn't been generated yet has nothing to launch, so it's filtered out here
// rather than shown disabled - Transmute > Generate is where that step lives.
const LaunchProjectListPage = () => {
    const [rows, setRows] = useState(null); // null while loading, [] once loaded (possibly empty)
    const [error, setError] = useState(null);
    // modelId -> { phase: 'launching'|'pairing'|'done', launchedBaseUrl, port, processKey,
    //              pairResult, pairWarning, error }
    const [rowState, setRowState] = useState({});

    const load = useCallback(async () => {
        setRows(null);
        setError(null);
        try {
            const response = await listModelSummaries();
            const processes = response.data || response || [];
            // A process only belongs on this page once GENERATE has actually completed - that's
            // also where the generated project id (what launchProject needs) comes from.
            const withGenerateState = await Promise.all(processes.map(async (process) => {
                try {
                    const stateRes = await getWorkflowState(process.id);
                    const state = stateRes.data || stateRes;
                    const generateStage = state.stages?.GENERATE;
                    if (generateStage?.status !== "COMPLETED" || !generateStage.detail) {
                        return null;
                    }
                    return { ...process, generatedProjectId: generateStage.detail };
                } catch (err) {
                    return null;
                }
            }));
            setRows(withGenerateState.filter(Boolean));
        } catch (err) {
            setError(err.response?.data?.message || err.message);
            setRows([]);
        }
    }, []);
    useEffect(() => { load(); }, [load]);

    const patchRow = (modelId, patch) => {
        setRowState((prev) => ({ ...prev, [modelId]: { ...prev[modelId], ...patch } }));
    };

    // One click, three steps: deploy the generated Target Platform, then - on the SAME
    // businessKey - start a proxy instance and a twin instance on its own REST API (not the
    // Workbench backend; a fully separate app), which is what SignalBroadcaster/PairRegistry use
    // to recognize the two as partners and actually synchronize their shared signals over
    // RabbitMQ instead of each running unpaired, then open Cockpit to watch them. Only
    // RedCollarTP-style generated platforms expose /api/proxy/start and /api/twin/start - a
    // generic Target Harness Platform doesn't, so a pairing failure is a soft warning (the app is
    // launched and reachable either way), not a Launch failure.
    const handleLaunch = async (row) => {
        patchRow(row.id, {
            phase: "launching", error: null, pairWarning: null, launchedBaseUrl: null, pairResult: null,
        });

        let baseUrl;
        let port;
        let processKey;
        try {
            const res = await launchProject({ projectId: row.generatedProjectId });
            const launched = res.data || res;
            if (!launched.port) {
                throw new Error("Launch succeeded but no port was returned");
            }
            // Same host as this Workbench frontend, launched port - never a hardcoded host/port.
            port = launched.port;
            processKey = launched.processKey;
            baseUrl = `${window.location.protocol}//${window.location.hostname}:${port}/`;
        } catch (err) {
            patchRow(row.id, { phase: null, error: "Launch failed: " + (err.response?.data?.message || err.message) });
            return;
        }

        patchRow(row.id, { phase: "pairing", launchedBaseUrl: baseUrl, port, processKey });
        const businessKey = `sync-${Date.now()}`;
        try {
            const startSide = async (side) => {
                const response = await fetch(`${baseUrl}api/${side}/start?businessKey=${businessKey}`, {
                    method: "POST",
                });
                if (!response.ok) {
                    throw new Error(`${side} start failed (HTTP ${response.status})`);
                }
                return response.json();
            };
            const [proxy, twin] = await Promise.all([startSide("proxy"), startSide("twin")]);
            patchRow(row.id, { phase: "done", pairResult: { businessKey, proxy, twin } });
        } catch (err) {
            patchRow(row.id, {
                phase: "done",
                pairWarning: "Launched, but could not auto-start proxy + twin (" + err.message
                    + ") - this generated platform may not support pairing.",
            });
        }
        openCockpitUrl(`${baseUrl}camunda/app/cockpit/engine/`);
    };

    return (
        <Container className="pt-5 mt-4">
            <h3 className="mb-1">Launch</h3>
            <p className="text-muted">
                Deploy an already-generated Target Platform, start a paired proxy + twin instance on it, and
                open Cockpit to watch them synchronize over RabbitMQ - all in one click.
            </p>
            {rows === null && <ProcessSpinner message="Loading generated processes..." />}
            {rows !== null && error && <Alert variant="danger">{error}</Alert>}
            {rows !== null && !error && rows.length === 0 && (
                <NoDataAvailable dataType="generated processes"
                    errorMessage="Nothing has been generated yet - generate a process first." />
            )}
            {rows !== null && !error && rows.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Process ID</th>
                            <th>Process name</th>
                            <th>Project</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row) => {
                            const rs = rowState[row.id] || {};
                            const busy = rs.phase === "launching" || rs.phase === "pairing";
                            const launchLabel = rs.phase === "launching" ? "Launching…"
                                : rs.phase === "pairing" ? "Starting proxy + twin…"
                                    : "Launch";
                            return (
                                <React.Fragment key={row.id}>
                                    <tr>
                                        <td className="text-muted small">{row.id}</td>
                                        <td>{row.name || "Untitled"}</td>
                                        <td>
                                            {row.projectDisplayName
                                                ? `${row.projectDisplayName} (${row.projectId})`
                                                : row.projectId ?? "-"}
                                        </td>
                                        <td className="text-end">
                                            <Button
                                                size="sm"
                                                variant="primary"
                                                disabled={busy}
                                                onClick={() => handleLaunch(row)}
                                            >
                                                {launchLabel}
                                            </Button>
                                        </td>
                                    </tr>
                                    {/* Own row below the button - result, never sharing a line
                                        with the button that produced it. */}
                                    {(rs.error || rs.launchedBaseUrl) && (
                                        <tr>
                                            <td colSpan={4} className="pt-0">
                                                {rs.error && <div className="text-danger small mb-1">{rs.error}</div>}
                                                {rs.launchedBaseUrl && (
                                                    <div className="d-flex align-items-center flex-wrap gap-2">
                                                        <span className="text-success small">
                                                            Launched on port {rs.port} (process "{rs.processKey || "?"}")
                                                        </span>
                                                        <Button size="sm" variant="outline-secondary"
                                                            onClick={() => openCockpitUrl(`${rs.launchedBaseUrl}camunda/app/cockpit/engine/`)}>
                                                            Open Cockpit
                                                        </Button>
                                                    </div>
                                                )}
                                                {rs.pairResult && (
                                                    <div className="text-muted small mt-1">
                                                        proxy + twin started — businessKey{" "}
                                                        <code>{rs.pairResult.businessKey}</code> — proxy:{" "}
                                                        <code>{rs.pairResult.proxy.processInstanceId}</code> (
                                                        {rs.pairResult.proxy.role}), twin:{" "}
                                                        <code>{rs.pairResult.twin.processInstanceId}</code> (
                                                        {rs.pairResult.twin.role})
                                                    </div>
                                                )}
                                                {rs.pairWarning && (
                                                    <div className="text-warning small mt-1">{rs.pairWarning}</div>
                                                )}
                                            </td>
                                        </tr>
                                    )}
                                </React.Fragment>
                            );
                        })}
                    </tbody>
                </Table>
            )}
        </Container>
    );
};

export default LaunchProjectListPage;
