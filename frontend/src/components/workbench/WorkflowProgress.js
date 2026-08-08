import React from "react";
import "./WorkflowProgress.css";

const PHASES = ["Model", "Generate", "Launch"];

// New scope item 1: "Add a workflow progress indicator (Model -> Generate -> Launch)" and
// "Display the current workflow phase within the editor." currentPhase is the INDEX of the last
// completed phase (0 = model saved, 1 = project generated, 2 = launched), or -1 if nothing's
// happened yet - not a phase name, so the editor can't drift out of sync with what "done" and
// "current" actually mean here as more phases get added later.
const WorkflowProgress = ({ currentPhase }) => {
    return (
        <div className="workflow-progress" role="navigation" aria-label="Model to Generate to Launch progress">
            {PHASES.map((phase, index) => {
                const isDone = index <= currentPhase;
                const isCurrent = index === currentPhase + 1;
                return (
                    <React.Fragment key={phase}>
                        {index > 0 && <span className="workflow-progress-arrow">&rarr;</span>}
                        <span
                            className={
                                "workflow-progress-step" +
                                (isDone ? " workflow-progress-step-done" : "") +
                                (isCurrent ? " workflow-progress-step-current" : "")
                            }
                        >
                            {phase}
                        </span>
                    </React.Fragment>
                );
            })}
        </div>
    );
};

export default WorkflowProgress;
