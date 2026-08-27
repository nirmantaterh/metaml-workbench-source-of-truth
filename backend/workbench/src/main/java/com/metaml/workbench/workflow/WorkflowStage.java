package com.metaml.workbench.workflow;

// The three real steps of the Model -> Generate -> Launch pipeline, in order. Deliberately just these three - Connect/Evolve/Bridge are a different, older feature (the twin workflow) that doesn't belong on this breadcrumb.
public enum WorkflowStage {
    MODEL,
    GENERATE,
    LAUNCH
}
