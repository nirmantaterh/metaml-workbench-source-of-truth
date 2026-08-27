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
    // Process definition identifier for the twin instance.
    private String twinProcessDefinitionId;
    private String originalProcessId;
    private String twinProcessId;
    // Target platform automation project identifier.
    private String projectId = "default";
    // Tenant ownership identifier.
    private String tenantId;
    private String status;
    private List<ActivityLink> activityLinks = new CopyOnWriteArrayList<>();
    private Instant launchedAt;
    private List<String> eventLog = new CopyOnWriteArrayList<>();

    // Resolves mapped twin activity ID for an original activity ID.
    public Optional<String> findTwinActivityId(String originalActivityId) {
        return activityLinks.stream()
                .filter(link -> link.getOriginalActivityId().equals(originalActivityId))
                .map(ActivityLink::getTwinActivityId)
                .findFirst();
    }
}
