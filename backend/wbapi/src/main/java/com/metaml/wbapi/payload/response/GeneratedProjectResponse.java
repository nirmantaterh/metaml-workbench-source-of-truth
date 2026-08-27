package com.metaml.wbapi.payload.response;

import com.metaml.workbench.generation.GeneratedProject;

// Plain-string mirror of GeneratedProject - Path doesn't serialize to JSON in any form a frontend would want, so this is what the generate-project endpoint actually hands back.
public record GeneratedProjectResponse(String projectId, String directory, String processKey, String displayName) {

    public GeneratedProjectResponse(String projectId, String directory, String processKey) {
        this(projectId, directory, processKey, null);
    }

    public static GeneratedProjectResponse from(GeneratedProject project) {
        return new GeneratedProjectResponse(project.projectId(), project.directory().toString(),
                project.processKey(), project.displayName());
    }
}
