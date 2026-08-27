package com.metaml.workbench.generation;

import java.time.Instant;

// public-facing info about a running generated app - deliberately doesn't carry the Process handle itself, since nothing outside SpringBootProjectLauncher should be able to touch that. modelId is nullable and NOT known by SpringBootProjectLauncher itself (it only ever sees a GeneratedProject, a lower-level concept with no notion of "model") - WorkbenchServiceImpl fills it in afterward from its own modelIdByProjectId map. Null there means exactly what it looks like: this project was generated/launched before the current backend session (a restart wipes that map, same as everything else not backed by WorkbenchStateStore), so there's nothing to link this running app back to a model with. New scope item 5 (Evolve Workflow) is what actually needs this - "connect to an existing deployed application" has to be able to point back at the model that produced it.
public record LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId) {
}
