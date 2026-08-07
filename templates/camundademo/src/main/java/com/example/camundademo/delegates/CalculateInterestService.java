package com.example.camundademo.delegates;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("calculateInterestService")
public class CalculateInterestService implements JavaDelegate {
    @Override
    public void execute(DelegateExecution arg0) throws Exception {
        System.out.println("****************** CalculateInterestService ---- Spring Bean invoked");
    }
}
