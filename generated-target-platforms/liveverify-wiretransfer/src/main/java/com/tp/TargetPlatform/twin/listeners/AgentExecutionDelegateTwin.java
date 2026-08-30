package com.tp.TargetPlatform.twin.listeners;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.springframework.stereotype.Component;

@Component("agentExecutionDelegateTwin")
public class AgentExecutionDelegateTwin implements TaskListener {
    @Override
    public void notify(DelegateTask delegateTask) {
        System.out.println("******************** TWIN (TASK LISTENER) - agentExecutionDelegateTwin ---- Spring Bean invoked");
    }
}
