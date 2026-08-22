package com.tp.TargetPlatform.twin.delegates;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("orderMgmtInitializationTwin")
public class OrderMgmtInitializationTwin implements JavaDelegate {
    @Override
    public void execute(DelegateExecution arg0) throws Exception {
        System.out.println("******************** TWIN - orderMgmtInitializationTwin ---- Spring Bean invoked");
    }
}
