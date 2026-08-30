package com.tp.TargetPlatform.worker.proxy;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "Marking" (BPMN activity "Marking").
@Component
public class MarkingWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(MarkingWorker.class);

    @Override
    public String topic() {
        return "Marking";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        logger.info("Executing generated external-task worker for activity \"Marking\" "
                + "(process instance {})", task.getProcessInstanceId());
        externalTaskService.complete(task.getId(), "generated-worker");
    }
}
