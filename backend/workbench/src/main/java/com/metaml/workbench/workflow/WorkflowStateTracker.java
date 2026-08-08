package com.metaml.workbench.workflow;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Single source of truth for where a model's Model -> Generate -> Launch pipeline actually is.
// The event log (one CopyOnWriteArrayList per model id) IS the state - "current stage" and "each
// stage's status" are both just a fold over it, computed fresh on every read, not separate fields
// that could drift out of sync with what was actually recorded. Callers (WorkbenchServiceImpl)
// record an event at the start and the end of each stage; nothing here decides on its own when a
// stage happens, it only remembers what it was told.
//
// Not persisted across a restart, same as everything else in this class's neighborhood
// (processModels, generatedProjects, the launcher's own running map) - a restart already forgets
// the generated project directories and any launched process, so a workflow history describing
// stages that no longer have anything real behind them would be actively misleading to keep.
@Component
public class WorkflowStateTracker {

    private final Map<String, List<StageEvent>> eventsByModelId = new ConcurrentHashMap<>();

    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail) {
        eventsByModelId.computeIfAbsent(modelId, id -> new CopyOnWriteArrayList<>())
                .add(new StageEvent(stage, status, Instant.now(), detail));
    }

    // never throws for an unknown modelId - a model with no recorded events yet (nothing has ever
    // called saveProcessModel for it) just reads as "everything pending", which is the honest
    // answer, not a 404
    public WorkflowState stateFor(String modelId) {
        List<StageEvent> history = eventsByModelId.getOrDefault(modelId, List.of());

        Map<WorkflowStage, StageInfo> stages = new EnumMap<>(WorkflowStage.class);
        for (WorkflowStage stage : WorkflowStage.values()) {
            stages.put(stage, latestFor(history, stage));
        }

        return new WorkflowState(modelId, resolveCurrentStage(stages), stages, new ArrayList<>(history));
    }

    // last event recorded for this stage wins - a retried Generate after a FAILED attempt
    // overwrites the stage's resolved status back to IN_PROGRESS/COMPLETED, while the FAILED
    // event stays in history underneath it
    private static StageInfo latestFor(List<StageEvent> history, WorkflowStage stage) {
        StageInfo latest = StageInfo.PENDING;
        for (StageEvent event : history) {
            if (event.stage() == stage) {
                latest = new StageInfo(event.status(), event.timestamp(), event.detail());
            }
        }
        return latest;
    }

    // The stage the breadcrumb should highlight as "where you are right now": something actively
    // running takes priority over everything else, then a blocker (the earliest stage that
    // failed, since that's what's actually stopping the pipeline from moving forward) takes
    // priority over stages further along that only look further along because an earlier retry
    // hasn't happened yet, and otherwise it's the stage right after the furthest one that's
    // actually finished.
    private static WorkflowStage resolveCurrentStage(Map<WorkflowStage, StageInfo> stages) {
        for (WorkflowStage stage : WorkflowStage.values()) {
            if (stages.get(stage).status() == StageStatus.IN_PROGRESS) {
                return stage;
            }
        }
        for (WorkflowStage stage : WorkflowStage.values()) {
            if (stages.get(stage).status() == StageStatus.FAILED) {
                return stage;
            }
        }
        WorkflowStage furthestDone = null;
        for (WorkflowStage stage : WorkflowStage.values()) {
            StageStatus status = stages.get(stage).status();
            if (status == StageStatus.COMPLETED || status == StageStatus.STOPPED) {
                furthestDone = stage;
            }
        }
        if (furthestDone == null) {
            return WorkflowStage.MODEL;
        }
        WorkflowStage[] order = WorkflowStage.values();
        int nextIndex = furthestDone.ordinal() + 1;
        return nextIndex < order.length ? order[nextIndex] : furthestDone;
    }
}
