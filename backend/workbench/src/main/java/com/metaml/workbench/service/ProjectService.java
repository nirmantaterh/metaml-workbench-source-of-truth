package com.metaml.workbench.service;

import java.util.List;

import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;

public interface ProjectService {
    Project createProject(ProjectDto projectDto);

    List<ProjectDto> getAllProjects();

    Project getProjectById(Long id);

    void saveProject(Project project);
}
