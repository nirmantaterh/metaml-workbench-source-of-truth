package com.example.camundademo.controller;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.camundademo.utils.restmappings.BPMNProcessRESTMappings;

@RestController
@RequestMapping(BPMNProcessRESTMappings.LOAN_APPROVAL_PROCESS)
public class Camundacontroller {
    @Autowired
    private final RuntimeService runtimeService;

    public Camundacontroller(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    // create a rest api to start the process instance
    @PostMapping(BPMNProcessRESTMappings.START_LOAN_PROCESS)
    public String startProcessInstance() {
        // Start the process instance using Camunda's RuntimeService
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("loanApproval");
        return "Process instance started with ID: " + processInstance.getId();
    }
}
