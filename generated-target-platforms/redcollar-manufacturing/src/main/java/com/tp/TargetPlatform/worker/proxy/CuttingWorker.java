package com.tp.TargetPlatform.worker.proxy;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "Cutting" (BPMN activity "Cutting").
@Component
public class CuttingWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(CuttingWorker.class);

    @Override
    public String topic() {
        return "Cutting";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        logger.info("Executing generated external-task worker for activity \"Cutting\" "
                + "(process instance {})", task.getProcessInstanceId());
        externalTaskService.complete(task.getId(), "generated-worker");
    }
}
