package com.metaml.workbench.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import com.metaml.workbench.dto.EntityConverter;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.repository.ProjectRepository;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectAttributesMapper projectAttributesMapper;
    private final EntityConverter<Project, ProjectDto> entityConverter;

    @Override
    public Project createProject(ProjectDto projectDto) {
        Project project = new Project();
        projectAttributesMapper.setCommonAttributes(projectDto, project);
        return projectRepository.save(project);
    }

    @Override
    public List<ProjectDto> getAllProjects() {
        return projectRepository.findAll().stream()
                .map(project -> entityConverter.mapEntityToDto(project, ProjectDto.class))
                .collect(Collectors.toList());
    }

    @Override
    public Project getProjectById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found: " + id));
    }

    @Override
    public void saveProject(Project project) {
        projectRepository.save(project);
    }
}
