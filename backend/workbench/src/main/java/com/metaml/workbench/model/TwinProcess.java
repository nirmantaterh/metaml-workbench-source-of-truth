package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwinProcess {
    private String id;
    private String modelId;
    private String processDefinitionId;
    // what the twin instance is actually running. Equal to processDefinitionId above on a twin
    // launched the plain way, where both instances share the one deployed definition, and a
    // separate generated definition on a twin whose activities were made executable.
    private String twinProcessDefinitionId;
    private String originalProcessId;
    private String twinProcessId;
    // which ProjectAutomationService bean the twin's activities run. There's no UI for picking one
    // and no second implementation to pick, so this is a field with a sensible value rather than a
    // feature - it's here so the next project attaching to the workbench has somewhere to put its
    // own name.
    private String projectId = "default";
    private String status;
    private List<ActivityLink> activityLinks = new CopyOnWriteArrayList<>();
    private Instant launchedAt;
    private List<String> eventLog = new CopyOnWriteArrayList<>();

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
