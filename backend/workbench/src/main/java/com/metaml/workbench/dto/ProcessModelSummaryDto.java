package com.metaml.workbench.dto;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

// The Project page needs a small catalogue entry, never the full BPMN XML. projectId/
// projectDisplayName are null for the Project-scoped listing (getProjectProcesses), which
// already knows which project it's asking about - populated only for the cross-project listing
// (listProcessModelSummaries) that backs the Transmute > Generate / Launch pickers, where each
// row has to say which project it belongs to on its own.
@Data
@NoArgsConstructor
public class ProcessModelSummaryDto {
    private String id;
    private String name;
    private LocalDateTime createdAt;
    private Long projectId;
    private String projectDisplayName;

    public ProcessModelSummaryDto(String id, String name, LocalDateTime createdAt) {
        this(id, name, createdAt, null, null);
    }

    public ProcessModelSummaryDto(String id, String name, LocalDateTime createdAt, Long projectId,
            String projectDisplayName) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.projectId = projectId;
        this.projectDisplayName = projectDisplayName;
    }
}
