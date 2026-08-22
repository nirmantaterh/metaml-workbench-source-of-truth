package com.metaml.workbench.service;

import org.springframework.stereotype.Component;

import com.metaml.workbench.constants.ProjectStatus;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;

@Component
public class ProjectAttributesMapper {

    public void setCommonAttributes(ProjectDto source, Project target) {
        target.setName(source.getName());
        target.setDisplayName(source.getDisplayName());
        target.setDescription(source.getDescription());
        target.setStatus(source.getStatus() != null ? source.getStatus() : ProjectStatus.PROJECT_CREATED);
    }
}
