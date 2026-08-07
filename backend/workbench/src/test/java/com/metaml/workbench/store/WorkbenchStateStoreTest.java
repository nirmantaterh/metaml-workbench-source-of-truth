package com.metaml.workbench.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.model.TwinProcess;

class WorkbenchStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void olderSnapshotsWithoutTwinProcessDefinitionIdStillRestoreTheOriginalDefinition() throws Exception {
        Path stateFile = tempDir.resolve("workbench-state.json");
        Files.writeString(stateFile, """
                {
                  "models": [],
                  "twins": [
                    {
                      "id": "twin-1",
                      "modelId": "model-1",
                      "processDefinitionId": "original-def",
                      "originalProcessId": "original-instance",
                      "twinProcessId": "twin-instance",
                      "status": "RUNNING",
                      "eventLog": [],
                      "activityLinks": []
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        WorkbenchStateStore.Snapshot snapshot = store.load();

        assertThat(snapshot.twins()).hasSize(1);
        assertThat(snapshot.twins().get(0).getTwinProcessDefinitionId()).isEqualTo("original-def");
    }

    // Phase 9/10 red team finding: save() used to build its DTO snapshot from the live models/twins
    // collections OUTSIDE the write lock, only synchronizing the actual file write. Since
    // WorkbenchServiceImpl always passes the SAME live, mutable collections on every call (not a
    // frozen copy per call), two concurrent persistState() calls could interleave their
    // snapshot-then-write sequences so a snapshot taken before some mutation could still win the
    // write lock AFTER a snapshot taken after that mutation had already written it - a lost update.
    // Snapshotting now happens inside the same lock as the write, so whichever caller acquires the
    // lock second always reads the CURRENT (already-mutated) live state, not a stale pre-captured
    // one - the file can only move forward, never regress, under concurrent saves of the same
    // live, growing state. Proven under real contention: many threads each append one more unique
    // entry to a SHARED TwinProcess's event log and immediately save() the same live twin
    // collection, all racing for the one write lock - every entry must still be present in the
    // final file, none silently lost to an overtaking older write.
    @Test
    void concurrentSavesOfTheSameLiveGrowingStateNeverLoseAnAlreadyWrittenEntry() throws Exception {
        Path stateFile = tempDir.resolve("workbench-state.json");
        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        TwinProcess sharedTwin = new TwinProcess();
        sharedTwin.setId("twin-1");
        sharedTwin.setModelId("model-1");
        sharedTwin.setProcessDefinitionId("original-def");
        sharedTwin.setTwinProcessDefinitionId("twin-def");
        sharedTwin.setOriginalProcessId("original-instance");
        sharedTwin.setTwinProcessId("twin-instance");
        sharedTwin.setStatus("RUNNING");

        int threadCount = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            AtomicInteger nextEntry = new AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    sharedTwin.getEventLog().add("entry-" + nextEntry.getAndIncrement());
                    store.save(List.of(), List.of(sharedTwin));
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        WorkbenchStateStore.Snapshot snapshot = store.load();
        assertThat(snapshot.twins()).hasSize(1);
        // the file's own last write might not have raced everyone, but by the time all threads
        // finish, the twin's own in-memory eventLog already holds all 24 - the property under test
        // is whether the LAST successful save() call's write reflects that full state, not a
        // regression back to some earlier, smaller snapshot
        assertThat(snapshot.twins().get(0).getEventLog()).hasSize(threadCount);
    }
}
