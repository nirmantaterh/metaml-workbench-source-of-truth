import React from "react";
import "./WorkflowProgress.css";

const PHASES = ["MODEL", "GENERATE", "LAUNCH"];
const PHASE_LABELS = { MODEL: "Model", GENERATE: "Generate", LAUNCH: "Launch" };

// New scope item 1's breadcrumb, redone to consume real backend state instead of guessing from
// local component variables (see WorkbenchService.getWorkflowState / the backend's
// WorkflowStateTracker) - this component does no inference of its own, it only renders whatever
// stage/status pairs it's handed. currentStage and stages both come straight off the API response
// shape: { currentStage: "GENERATE", stages: { MODEL: {status, timestamp, detail}, ... } }.
const WorkflowProgress = ({ currentStage, stages }) => {
    const statusFor = (phase) => stages?.[phase]?.status || "PENDING";

    return (
        <div className="workflow-progress" role="navigation" aria-label="Model to Generate to Launch progress">
            {PHASES.map((phase, index) => {
                const status = statusFor(phase);
                const isCurrent = phase === currentStage;
                const stageInfo = stages?.[phase];
                const title = stageInfo?.detail
                    ? `${status}: ${stageInfo.detail}`
                    : stageInfo?.timestamp
                    ? `${status} at ${new Date(stageInfo.timestamp).toLocaleString()}`
                    : status;
                return (
                    <React.Fragment key={phase}>
                        {index > 0 && <span className="workflow-progress-arrow">&rarr;</span>}
                        <span
                            className={
                                "workflow-progress-step" +
                                (status === "COMPLETED" || status === "STOPPED" ? " workflow-progress-step-done" : "") +
                                (status === "FAILED" ? " workflow-progress-step-failed" : "") +
                                (status === "IN_PROGRESS" ? " workflow-progress-step-inprogress" : "") +
                                (isCurrent ? " workflow-progress-step-current" : "")
                            }
                            title={title}
                        >
                            {PHASE_LABELS[phase]}
                            {status === "FAILED" && " ⚠"}
                        </span>
                    </React.Fragment>
                );
            })}
        </div>
    );
};

export default WorkflowProgress;
