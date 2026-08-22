package com.metaml.workbench.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

// The Project page needs a small catalogue entry, never the full BPMN XML.
@Data
@AllArgsConstructor
public class ProcessModelSummaryDto {
    private String id;
    private String name;
    private LocalDateTime createdAt;
}
