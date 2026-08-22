package com.tp.TargetPlatform.proxy.delegates;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("pressingProxy")
public class PressingProxy implements JavaDelegate {
    @Override
    public void execute(DelegateExecution arg0) throws Exception {
        System.out.println("******************** PROXY - pressingProxy ---- Spring Bean invoked");
    }
}
