package com.metaml.workbench.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkflowStateTrackerTest {

    private final WorkflowStateTracker tracker = new WorkflowStateTracker();

    @Test
    void aModelWithNoHistoryReadsAsEverythingPendingNotAnError() {
        WorkflowState state = tracker.stateFor("never-touched");

        assertThat(state.currentStage()).isEqualTo(WorkflowStage.MODEL);
        assertThat(state.stages().get(WorkflowStage.MODEL).status()).isEqualTo(StageStatus.PENDING);
        assertThat(state.stages().get(WorkflowStage.GENERATE).status()).isEqualTo(StageStatus.PENDING);
        assertThat(state.stages().get(WorkflowStage.LAUNCH).status()).isEqualTo(StageStatus.PENDING);
        assertThat(state.history()).isEmpty();
    }

    @Test
    void currentStageAdvancesPastEachCompletedStage() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);

        assertThat(tracker.stateFor("m1").currentStage()).isEqualTo(WorkflowStage.GENERATE);

        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.COMPLETED, "project-123");

        assertThat(tracker.stateFor("m1").currentStage()).isEqualTo(WorkflowStage.LAUNCH);

        tracker.record("m1", WorkflowStage.LAUNCH, StageStatus.COMPLETED, "port 4567");

        // nothing after LAUNCH - once every stage is done, current stays put on the last one
        assertThat(tracker.stateFor("m1").currentStage()).isEqualTo(WorkflowStage.LAUNCH);
        assertThat(tracker.stateFor("m1").stages().get(WorkflowStage.LAUNCH).detail()).isEqualTo("port 4567");
    }

    @Test
    void anInProgressStageIsAlwaysCurrentRegardlessOfWhatElseHappened() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.IN_PROGRESS, null);

        assertThat(tracker.stateFor("m1").currentStage()).isEqualTo(WorkflowStage.GENERATE);
        assertThat(tracker.stateFor("m1").stages().get(WorkflowStage.GENERATE).status())
                .isEqualTo(StageStatus.IN_PROGRESS);
    }

    // a failed stage is the actual blocker - the breadcrumb should point at it, not silently skip
    // ahead to whatever the next pending stage would otherwise be
    @Test
    void aFailedStageBecomesCurrentWithItsErrorAttached() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.IN_PROGRESS, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.FAILED, "no template project found");

        WorkflowState state = tracker.stateFor("m1");
        assertThat(state.currentStage()).isEqualTo(WorkflowStage.GENERATE);
        assertThat(state.stages().get(WorkflowStage.GENERATE).status()).isEqualTo(StageStatus.FAILED);
        assertThat(state.stages().get(WorkflowStage.GENERATE).detail()).isEqualTo("no template project found");
    }

    // a retry after a failure has to actually win - the failed attempt shouldn't haunt the
    // breadcrumb forever once the real problem is fixed and it succeeds
    @Test
    void aSuccessfulRetryAfterAFailureResolvesAsCompletedNotFailed() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.FAILED, "boom");
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.IN_PROGRESS, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.COMPLETED, "project-456");

        WorkflowState state = tracker.stateFor("m1");
        assertThat(state.currentStage()).isEqualTo(WorkflowStage.LAUNCH);
        assertThat(state.stages().get(WorkflowStage.GENERATE).status()).isEqualTo(StageStatus.COMPLETED);
        // the failure isn't erased - it's still there for anyone debugging why this took two tries
        assertThat(state.history()).extracting(StageEvent::status)
                .contains(StageStatus.FAILED, StageStatus.IN_PROGRESS, StageStatus.COMPLETED);
    }

    @Test
    void stoppingALaunchedProjectResolvesToStoppedNotCompletedOrPending() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        tracker.record("m1", WorkflowStage.GENERATE, StageStatus.COMPLETED, "project-1");
        tracker.record("m1", WorkflowStage.LAUNCH, StageStatus.COMPLETED, "port 8080");
        tracker.record("m1", WorkflowStage.LAUNCH, StageStatus.STOPPED, null);

        assertThat(tracker.stateFor("m1").stages().get(WorkflowStage.LAUNCH).status())
                .isEqualTo(StageStatus.STOPPED);
    }

    @Test
    void differentModelsHaveCompletelyIndependentHistories() {
        tracker.record("m1", WorkflowStage.MODEL, StageStatus.COMPLETED, null);

        assertThat(tracker.stateFor("m2").currentStage()).isEqualTo(WorkflowStage.MODEL);
        assertThat(tracker.stateFor("m2").stages().get(WorkflowStage.MODEL).status())
                .isEqualTo(StageStatus.PENDING);
    }
}
