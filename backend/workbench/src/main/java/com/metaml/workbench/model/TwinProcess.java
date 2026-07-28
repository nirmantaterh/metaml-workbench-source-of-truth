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
    // activities the bridge already forwarded, so a repeat is a no-op instead of eating more
    // quota. add() is atomic, so two at once still only get one through.
    private Set<String> forwardedBridgeActivities = ConcurrentHashMap.newKeySet();

    // three different call sites needed this same lookup, so it lives here now instead of
    // copy-pasted in each one
    public String resolveTwinActivityId(String originalActivityId) {
        return activityLinks.stream()
                .filter(link -> link.getOriginalActivityId().equals(originalActivityId))
                .map(ActivityLink::getTwinActivityId)
                .findFirst()
                .orElse(originalActivityId);
    }
}
