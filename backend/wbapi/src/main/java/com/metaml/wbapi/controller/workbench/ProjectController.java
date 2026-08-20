package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import static org.springframework.http.HttpStatus.*;

import java.util.List;

import com.metaml.workbench.dto.EntityConverter;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.service.ProjectService;
import com.metaml.wbapi.exception.AlreadyExistsException;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.utils.FeedbackMessage;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;

@RestController
@RequestMapping(WorkbenchUrlMapping.PROJECT)
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final EntityConverter<Project, ProjectDto> entityConverter;

    @PostMapping(WorkbenchUrlMapping.CREATE_PROJECT)
    public ResponseEntity<ApiResponse> createProject(@RequestBody ProjectDto request) {
        try {
            Project project = projectService.createProject(request);
            ProjectDto newProject = entityConverter.mapEntityToDto(project, ProjectDto.class);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.CREATE_PROJECT_SUCCESS, newProject));
        } catch (AlreadyExistsException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.GET_ALL_PROJECTS)
    public List<ProjectDto> getAllProjects() {
        return projectService.getAllProjects();
    }
}
