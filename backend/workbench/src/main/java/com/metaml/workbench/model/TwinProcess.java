package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwinProcess {
    private String id;
    private String modelId;
    private String processDefinitionId;
    private String originalProcessId;
    private String twinProcessId;
    private String status;
    private List<ActivityLink> activityLinks = new CopyOnWriteArrayList<>();
    private Instant launchedAt;
    private List<String> eventLog = new CopyOnWriteArrayList<>();
    // Tracks which original activity ids the bridge has already forwarded for this twin, so a
    // repeat bridge call is a no-op instead of re-reserving governance quota and re-contacting
    // the node manager. Set.add() is atomic, so concurrent duplicate bridge calls for the same
    // activity only let one through.
    private Set<String> forwardedBridgeActivities = ConcurrentHashMap.newKeySet();
}
