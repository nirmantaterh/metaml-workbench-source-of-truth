package com.tp.TargetPlatform.worker.proxy;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "Pressing" (BPMN activity "Pressing").
@Component
public class PressingWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(PressingWorker.class);

    @Override
    public String topic() {
        return "Pressing";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        logger.info("Executing generated external-task worker for activity \"Pressing\" "
                + "(process instance {})", task.getProcessInstanceId());
        externalTaskService.complete(task.getId(), "generated-worker");
    }
}
