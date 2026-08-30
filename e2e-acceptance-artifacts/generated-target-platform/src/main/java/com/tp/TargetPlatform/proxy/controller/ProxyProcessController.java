package com.tp.TargetPlatform.proxy.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tp.TargetPlatform.coordination.PairRegistry;

// Starts and registers proxy process instances. businessKey is what pairs a proxy instance with its counterpart (see PairRegistry / SignalBroadcaster) - the first instance registered under a key is the initiator, the second is the responder, so starting a proxy and a twin with the SAME businessKey is what makes them synchronize.
@RestController
@RequestMapping("/api/proxy")
public class ProxyProcessController {

    private final RuntimeService runtimeService;
    private final PairRegistry pairRegistry;

    public ProxyProcessController(RuntimeService runtimeService, PairRegistry pairRegistry) {
        this.runtimeService = runtimeService;
        this.pairRegistry = pairRegistry;
    }

    @GetMapping("/health")
    public String health() {
        return "proxy ok";
    }

    // businessKey is optional - omit it to run a lone proxy instance with nothing to synchronize against (every signal falls back to immediate delivery); supply the SAME key on both sides' /start calls to pair them.
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(required = false) String businessKey) {
        String key = (businessKey == null || businessKey.isBlank())
                ? UUID.randomUUID().toString() : businessKey;
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("Process_WireTransfer", key);
        String role = pairRegistry.registerAndClassify(key, instance.getProcessInstanceId());
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceId", instance.getProcessInstanceId());
        body.put("businessKey", key);
        body.put("role", role == null ? "unpaired" : role);
        return body;
    }
}
