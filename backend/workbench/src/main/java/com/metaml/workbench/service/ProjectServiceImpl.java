package com.metaml.workbench.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.metaml.workbench.dto.EntityConverter;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectAttributesMapper projectAttributesMapper;
    private final EntityConverter<Project, ProjectDto> entityConverter;
    private final ProcessModelArchiveRepository archiveRepository;
    private final WorkbenchService workbenchService;

    @Override
    public Project createProject(ProjectDto projectDto) {
        if (projectDto == null) {
            throw new IllegalArgumentException("Project name must not be blank");
        }

        String displayName = projectDto.getDisplayName() != null && !projectDto.getDisplayName().isBlank()
                ? projectDto.getDisplayName().trim()
                : projectDto.getName();
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Project name must not be blank");
        }
        displayName = displayName.trim();
        if (projectDto.getDescription() != null && projectDto.getDescription().length() > 500) {
            throw new IllegalArgumentException("Project description must be at most 500 characters");
        }
        Project project = new Project();
        projectDto.setDisplayName(displayName);
        projectDto.setName(generateInternalName(displayName));
        projectAttributesMapper.setCommonAttributes(projectDto, project);
        return projectRepository.save(project);
    }

    @Override
    public List<ProjectDto> getAllProjects() {
        return projectRepository.findAll().stream()
                .peek(this::ensureDisplayName)
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
        ensureDisplayName(project);
        projectRepository.save(project);
    }

    @Override
    public List<ProcessModelSummaryDto> getProjectProcessModels(Long projectId) {
        getProjectById(projectId);
        return archiveRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(archive -> new ProcessModelSummaryDto(archive.getModelId(), archive.getName(), archive.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId) {
        Project project = getProjectById(projectId);
        List<String> modelIds = archiveRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(archive -> archive.getModelId())
                .collect(Collectors.toList());

        // Check every process before mutating anything: an active generated application makes the
        // whole project deletion fail, never a partly-deleted project.
        for (String modelId : modelIds) {
            if (!workbenchService.canDeleteProcessModel(modelId)) {
                throw new IllegalStateException("Cannot delete project " + project.getDisplayName()
                        + " - process model " + modelId
                        + " has a generated application running or being launched. Stop it first.");
            }
        }
        for (String modelId : modelIds) {
            workbenchService.deleteProcessModel(modelId);
        }
        projectRepository.delete(project);
    }

    private void ensureDisplayName(Project project) {
        if (project.getDisplayName() == null) {
            project.setDisplayName(project.getName());
        }
    }

    private String generateInternalName(String displayName) {
        String slug = displayName == null ? "" : displayName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "project" : slug;
    }
}
