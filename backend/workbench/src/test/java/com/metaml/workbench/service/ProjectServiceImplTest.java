package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.metaml.workbench.dto.EntityConverter;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProcessModelArchiveRepository archiveRepository;
    @Mock
    private EntityConverter<Project, ProjectDto> entityConverter;
    @Mock
    private WorkbenchService workbenchService;

    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectServiceImpl(projectRepository, new ProjectAttributesMapper(), entityConverter,
                archiveRepository, workbenchService);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            if (project.getId() == null) {
                project.setId(7L);
            }
            return project;
        });
    }

    @Test
    void createProjectUsesTheFriendlyNameAndGeneratesAnInternalSlug() {
        ProjectDto request = new ProjectDto();
        request.setDisplayName("RedCollar Suits");
        request.setDescription("RedCollar manual process for creating custom suits");

        Project created = service.createProject(request);

        assertThat(created.getName()).isEqualTo("redcollar_suits");
        assertThat(created.getDisplayName()).isEqualTo("RedCollar Suits");
        assertThat(created.getDescription()).isEqualTo("RedCollar manual process for creating custom suits");
    }

    @Test
    void createProjectStillAcceptsTheOldNameOnlyShape() {
        ProjectDto request = new ProjectDto();
        request.setName("Legacy Friendly Project");

        Project created = service.createProject(request);

        assertThat(created.getName()).isEqualTo("legacy_friendly_project");
        assertThat(created.getDisplayName()).isEqualTo("Legacy Friendly Project");
    }
}
