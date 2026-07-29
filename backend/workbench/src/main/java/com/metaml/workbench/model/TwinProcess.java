package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    // Camunda activity instance ids the bridge already forwarded, so a repeat is a no-op instead
    // of eating more quota. One entry per visit, not per activity, or a loop's second time round
    // looks like a duplicate. add() is atomic, so two at once still only get one through.
    private Set<String> forwardedBridgeActivities = ConcurrentHashMap.newKeySet();

    // empty when the activity was never connected. This used to fall back to the original id,
    // which was harmless for callers that had already checked but made AgentExecutionDelegate
    // report agents for activities nobody ever connected.
    public Optional<String> findTwinActivityId(String originalActivityId) {
        return activityLinks.stream()
                .filter(link -> link.getOriginalActivityId().equals(originalActivityId))
                .map(ActivityLink::getTwinActivityId)
                .findFirst();
    }
}
