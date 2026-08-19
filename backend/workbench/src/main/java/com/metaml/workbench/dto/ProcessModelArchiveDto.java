package com.metaml.workbench.dto;

import lombok.Data;
import java.time.LocalDateTime;

import com.metaml.workbench.model.Project;

@Data
public class ProcessModelArchiveDto {
    private Long id;
    private String name;
    private String bpmnXml;
    private String bpmnFilePath;
    private LocalDateTime createdAt;
    private String processDefinitionId;
    private String tenantId;
    private Integer major;
    private Integer minor;
    private Integer patch;
    private String preRelease;
    private String buildMetadata;
    private Project project;
}
