import React, { useEffect } from "react";
import "./WorkflowDetailsPanel.css";

const PHASES = ["MODEL", "GENERATE", "LAUNCH"];
const PHASE_LABELS = { MODEL: "Model", GENERATE: "Generate", LAUNCH: "Launch" };
const STATUS_ICON = { PENDING: "○", IN_PROGRESS: "●", COMPLETED: "✓", STOPPED: "✓", FAILED: "✕" };
const STATUS_WORD = {
    PENDING: "Pending",
    IN_PROGRESS: "In progress",
    COMPLETED: "Completed",
    STOPPED: "Stopped",
    FAILED: "Failed",
};

const formatTime = (isoTimestamp) => (isoTimestamp ? new Date(isoTimestamp).toLocaleTimeString() : null);

const formatDuration = (ms) => {
    if (ms < 1000) return `${ms}ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    return `${minutes}m ${remainder}s`;
};

// Everything here is read straight off the same WorkflowState ModelPage already polls for and
// hands to WorkflowProgress - this panel doesn't fetch anything of its own and doesn't infer
// anything the backend didn't say. The one thing it computes locally is "when did the current
// run of this stage start" and the duration that implies - that's arithmetic over real recorded
// timestamps (find the IN_PROGRESS event immediately before the stage's current terminal event
// in its own history), not an invented value.
const startOf = (history, stage) => {
    const forStage = (history || []).filter((event) => event.stage === stage);
    for (let i = forStage.length - 2; i >= 0; i--) {
        if (forStage[i].status === "IN_PROGRESS") return forStage[i].timestamp;
    }
    return null;
};

const WorkflowDetailsPanel = ({ workflowState, onClose }) => {
    useEffect(() => {
        const onKeyDown = (e) => {
            if (e.key === "Escape") onClose();
        };
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [onClose]);

    const stages = workflowState?.stages || {};
    const history = workflowState?.history || [];

    return (
        <div className="workflow-details-panel" role="dialog" aria-label="Workflow details">
            <div className="workflow-details-header">
                <span>Workflow Details</span>
                <button type="button" className="workflow-details-close" onClick={onClose} aria-label="Close">
                    &times;
                </button>
            </div>

            <div className="workflow-details-body">
                {PHASES.map((phase) => {
                    const info = stages[phase] || { status: "PENDING" };
                    const started = info.status === "IN_PROGRESS" ? info.timestamp : startOf(history, phase);
                    const completed =
                        info.status === "COMPLETED" || info.status === "FAILED" || info.status === "STOPPED"
                            ? info.timestamp
                            : null;
                    const durationMs =
                        started && completed ? new Date(completed).getTime() - new Date(started).getTime() : null;

                    return (
                        <div key={phase} className={`workflow-details-stage workflow-details-stage-${info.status.toLowerCase()}`}>
                            <div className="workflow-details-stage-title">
                                <span className="workflow-details-stage-icon">{STATUS_ICON[info.status]}</span>
                                {PHASE_LABELS[phase]}
                                <span className="workflow-details-stage-status">{STATUS_WORD[info.status]}</span>
                            </div>
                            {started && (
                                <div className="workflow-details-stage-line">
                                    {phase === "MODEL" ? "Saved" : "Started"}: {formatTime(started)}
                                </div>
                            )}
                            {completed && info.status !== "FAILED" && (
                                <div className="workflow-details-stage-line">Completed: {formatTime(completed)}</div>
                            )}
                            {info.status === "FAILED" && <div className="workflow-details-stage-line">Failed: {formatTime(completed)}</div>}
                            {durationMs !== null && (
                                <div className="workflow-details-stage-line">Duration: {formatDuration(durationMs)}</div>
                            )}
                            {info.status === "FAILED" && info.detail && (
                                <div className="workflow-details-error">
                                    <span className="workflow-details-eyebrow">Error</span>
                                    {info.detail}
                                </div>
                            )}
                        </div>
                    );
                })}

                <div className="workflow-details-eyebrow workflow-details-history-heading">Event History</div>
                {history.length === 0 ? (
                    <div className="workflow-details-empty">Nothing recorded yet.</div>
                ) : (
                    <div className="workflow-details-history">
                        {history.map((event, index) => (
                            // stage+status can repeat (a retry) so index is part of the key, not a
                            // workaround for missing data - there's no event id from the backend
                            <div key={`${event.stage}-${event.status}-${index}`} className="workflow-details-history-row">
                                <span className="workflow-details-history-time">{formatTime(event.timestamp)}</span>
                                <span className="workflow-details-history-stage">{PHASE_LABELS[event.stage]}</span>
                                <span className="workflow-details-history-status">{event.status}</span>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
};

export default WorkflowDetailsPanel;
