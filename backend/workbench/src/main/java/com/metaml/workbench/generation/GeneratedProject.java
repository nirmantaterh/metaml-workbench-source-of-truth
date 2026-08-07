package com.metaml.workbench.generation;

import java.nio.file.Path;

// One assembled, ready-to-run Spring Boot project on disk - the template copied, the saved
// model's BPMN dropped into its processes folder, and every generated delegate class written
// alongside it. Port is null until something actually launches this (SpringBootProjectGenerator
// only assembles files, it never starts a process).
public record GeneratedProject(String projectId, Path directory, String processKey) {
}
