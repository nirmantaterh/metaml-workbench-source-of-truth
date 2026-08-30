package com.tp.TargetPlatform.proxy.listeners;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.springframework.stereotype.Component;

@Component("agentExecutionDelegate")
public class AgentExecutionDelegate implements TaskListener {
    @Override
    public void notify(DelegateTask delegateTask) {
        System.out.println("******************** PROXY (TASK LISTENER) - agentExecutionDelegate ---- Spring Bean invoked");
    }
}
