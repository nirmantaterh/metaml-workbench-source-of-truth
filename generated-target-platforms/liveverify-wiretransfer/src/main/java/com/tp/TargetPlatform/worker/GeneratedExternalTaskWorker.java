package com.tp.TargetPlatform.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;

// Contract for generated external-task workers. Each worker handles one topic via the embedded engine's ExternalTaskService API (not the HTTP-based external-task client, which requires Jersey and is incompatible with Spring Boot 4.x).
public interface GeneratedExternalTaskWorker {

    String topic();

    void execute(LockedExternalTask task, ExternalTaskService externalTaskService);
}
