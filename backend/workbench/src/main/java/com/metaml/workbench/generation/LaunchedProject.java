package com.metaml.workbench.generation;

import java.time.Instant;

// public-facing info about a running generated app - deliberately doesn't carry the Process
// handle itself, since nothing outside SpringBootProjectLauncher should be able to touch that
public record LaunchedProject(String projectId, String processKey, int port, Instant launchedAt) {
}
