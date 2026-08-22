package com.tp.TargetPlatform.proxy.delegates;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("orderMgmtInitializationProxy")
public class OrderMgmtInitializationProxy implements JavaDelegate {
    @Override
    public void execute(DelegateExecution arg0) throws Exception {
        System.out.println("******************** PROXY - orderMgmtInitializationProxy ---- Spring Bean invoked");
    }
}
