package com.metaml.workbench.dto;

import lombok.Data;

import com.metaml.workbench.constants.ProjectStatus;

@Data
public class ProjectDto {
    private Long id;
    private String name;
    private String description;
    private ProjectStatus status;
}
