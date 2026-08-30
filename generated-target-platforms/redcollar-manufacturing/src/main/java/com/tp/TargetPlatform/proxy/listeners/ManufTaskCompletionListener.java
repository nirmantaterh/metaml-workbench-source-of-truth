package com.tp.TargetPlatform.proxy.listeners;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

@Component("manufTaskCompletionListener")
public class ManufTaskCompletionListener implements ExecutionListener {
    @Override
    public void notify(DelegateExecution execution) throws Exception {
        System.out.println("******************** PROXY (LISTENER) - manufTaskCompletionListener ---- Spring Bean invoked");
    }
}
