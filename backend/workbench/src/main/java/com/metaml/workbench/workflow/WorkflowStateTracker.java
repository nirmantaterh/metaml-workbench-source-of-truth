package com.metaml.workbench.workflow;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
// stage happens, it only remembers what it was told - and now checks that what it was told is a
// legal transition (see recordValidated below) before remembering it.
//
// Persisted via WorkflowEventStore (see its own header comment for why that's a separate class
// rather than folded into WorkbenchStateStore). Loaded once at startup, rewritten after every
// record() - the same "always fully durable, fine at demo scale" choice WorkbenchStateStore
// already makes for process models and twins.
@Component
public class WorkflowStateTracker {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStateTracker.class);

    private final Map<String, List<StageEvent>> eventsByModelId = new ConcurrentHashMap<>();
    private final WorkflowEventStore eventStore;

    public WorkflowStateTracker(WorkflowEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @PostConstruct
    void restore() {
        for (Map.Entry<String, List<StageEvent>> entry : eventStore.load().entrySet()) {
            eventsByModelId.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
        }
    }

    // The only entry point real callers (WorkbenchServiceImpl) use for a LIVE operation - validates
    // the transition is actually legal given what this stage (and its prerequisite) currently show,
    // rather than trusting that whoever's calling got the ordering right. This only ever protects
    // against a backend bug, not a hostile frontend - nothing outside this package can reach
    // record() at all, the REST layer only exposes reading state, never writing it.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail) {
        record(modelId, stage, status, detail, (StageError) null);
    }

    // Phase 3A: same live entry point, plus structured error info for a FAILED stage (see
    // StageError's own comment on when the caller actually has this to hand). Still just the one
    // event log underneath - error rides along on the same StageEvent as detail, not a second
    // record of what happened.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail, StageError error) {
        validateTransition(modelId, stage, status);
        append(modelId, new StageEvent(stage, status, Instant.now(), detail, error));
    }

    private void validateTransition(String modelId, WorkflowStage stage, StageStatus status) {
        Map<WorkflowStage, StageInfo> current = stateFor(modelId).stages();
        StageStatus currentStatus = current.get(stage).status();

        switch (status) {
            case PENDING -> throw new IllegalStateException(
                    "Refusing to record " + stage + "/PENDING for model " + modelId
                            + " - PENDING is the implicit default for a stage with no events, it should never be "
                            + "written explicitly");
            case IN_PROGRESS -> {
                WorkflowStage prerequisite = prerequisiteOf(stage);
                if (prerequisite != null && current.get(prerequisite).status() != StageStatus.COMPLETED) {
                    throw new IllegalStateException(stage + " cannot start for model " + modelId + " - "
                            + prerequisite + " has not completed (currently "
                            + current.get(prerequisite).status() + ")");
                }
            }
            case COMPLETED, FAILED -> {
                if (currentStatus != StageStatus.IN_PROGRESS) {
                    throw new IllegalStateException(
                            "Cannot record " + stage + "/" + status + " for model " + modelId
                                    + " - " + stage + " is not IN_PROGRESS (currently " + currentStatus + ")");
                }
            }
            case STOPPED -> {
                if (stage != WorkflowStage.LAUNCH) {
                    throw new IllegalStateException(
                            "STOPPED only applies to LAUNCH, not " + stage + " (model " + modelId + ")");
                }
                if (currentStatus != StageStatus.COMPLETED) {
                    throw new IllegalStateException("Cannot stop LAUNCH for model " + modelId
                            + " - it is not COMPLETED (currently " + currentStatus + ")");
                }
            }
        }
    }

    private static WorkflowStage prerequisiteOf(WorkflowStage stage) {
        WorkflowStage[] order = WorkflowStage.values();
        int index = stage.ordinal();
        return index == 0 ? null : order[index - 1];
    }

    // for backfilling a stage's event with a timestamp other than "now" - specifically, restoring
    // MODEL/COMPLETED for a model whose workflow history predates this class having real
    // persistence at all (see WorkbenchServiceImpl.restoreState's own comment on when it still
    // reaches for this). Backfilling with the model's own real createdAt rather than the restart
    // time keeps the history honest about when the model was actually first saved.
    //
    // Deliberately bypasses transition validation - a backfilled event describes something that
    // is already known to have happened in the past (the model demonstrably exists), not a live
    // operation whose ordering this class has any business second-guessing.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail, Instant timestamp) {
        append(modelId, new StageEvent(stage, status, timestamp, detail));
    }

    private void append(String modelId, StageEvent event) {
        eventsByModelId.computeIfAbsent(modelId, id -> new CopyOnWriteArrayList<>()).add(event);
        eventStore.save(eventsByModelId);
    }

    // true only for a model with zero persisted events of ANY kind - the signal
    // WorkbenchServiceImpl.restoreState() uses to decide whether a model predates real
    // persistence (needs the MODEL backfill) or already has its own genuine history restored from
    // WorkflowEventStore (backfilling on top of that would just be a redundant, slightly-wrong-
    // timestamped duplicate of an event that's already there)
    public boolean hasNoHistory(String modelId) {
        List<StageEvent> history = eventsByModelId.get(modelId);
        return history == null || history.isEmpty();
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
                latest = new StageInfo(event.status(), event.timestamp(), event.detail(), event.error());
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
