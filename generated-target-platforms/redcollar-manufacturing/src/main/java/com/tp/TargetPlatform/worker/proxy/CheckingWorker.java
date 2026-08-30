package com.tp.TargetPlatform.worker.proxy;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "Checking" (BPMN activity "Checking").
@Component
public class CheckingWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(CheckingWorker.class);

    @Override
    public String topic() {
        return "Checking";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
            logger.info("Executing generated external-task worker for activity \"Checking\" "
                    + "(process instance {})", task.getProcessInstanceId());
            Map<String, Object> variables = new HashMap<>();
            variables.put("qualityPassed", Math.random() > 0.5);
            logger.info("Worker completion variables: {}", variables);
            externalTaskService.complete(task.getId(), "generated-worker", variables);
    }
}
