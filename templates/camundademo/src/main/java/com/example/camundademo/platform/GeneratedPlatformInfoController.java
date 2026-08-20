package com.example.camundademo.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.FlowNode;

import com.example.camundademo.coordination.PairRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Generic, read-only introspection of THIS generated target platform's own deployed processes and
// recent runtime activity - built entirely from what Camunda actually has deployed/running, never
// from a per-BPMN generation-time template substitution. Ships with every generated project as
// part of the template itself (see SpringBootProjectGenerator's copyTemplate()/rewritePackage()
// step, the same idiom NotificationBridge already uses), so it works unmodified for a single-BPMN
// governance-twin platform, an authored Main+Twin platform, or any future BPMN pair - nothing here
// is specific to any one process's name, activities, or business data. Backs the static dashboard
// at src/main/resources/static/index.html.
@RestController
@RequestMapping("/api/v1/platform")
public class GeneratedPlatformInfoController {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final PairRegistry pairRegistry;
    private final boolean messagingEnabled;

    public GeneratedPlatformInfoController(RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, PairRegistry pairRegistry,
            @Value("${metaml.messaging.enabled:false}") boolean messagingEnabled) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.pairRegistry = pairRegistry;
        this.messagingEnabled = messagingEnabled;
    }

    // Every process definition actually deployed into this running application - one entry per
    // process key's latest version. No assumption about how many there are or what they're called:
    // a single-BPMN platform reports one, an authored Main+Twin platform reports two.
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        List<Map<String, Object>> processes = new ArrayList<>();
        for (ProcessDefinition definition : repositoryService.createProcessDefinitionQuery().latestVersion()
                .list()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("processDefinitionId", definition.getId());
            entry.put("processDefinitionKey", definition.getKey());
            entry.put("name", definition.getName() != null ? definition.getName() : definition.getKey());
            entry.put("version", definition.getVersion());
            entry.put("activities", activityNames(definition.getId()));
            long activeCount = runtimeService.createProcessInstanceQuery().processDefinitionId(definition.getId())
                    .count();
            entry.put("activeInstanceCount", activeCount);
            processes.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processes", processes);
        body.put("messagingEnabled", messagingEnabled);
        return ResponseEntity.ok(body);
    }

    // The most recently started process instances across every deployed process in this
    // application, active or completed, newest first - lets the UI surface real runtime activity,
    // including whatever business data (e.g. an order id) each instance was started with, without
    // the caller needing to already know an instance id.
    @GetMapping("/recent-instances")
    public ResponseEntity<List<Map<String, Object>>> recentInstances() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<HistoricProcessInstance> historic = historyService.createHistoricProcessInstanceQuery()
                .orderByProcessInstanceStartTime().desc().listPage(0, 25);
        for (HistoricProcessInstance instance : historic) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("processInstanceId", instance.getId());
            entry.put("processDefinitionKey", instance.getProcessDefinitionKey());
            entry.put("businessKey", instance.getBusinessKey());
            entry.put("startTime", instance.getStartTime());
            entry.put("endTime", instance.getEndTime());
            boolean active = instance.getEndTime() == null;
            entry.put("active", active);
            entry.put("variables", active ? safeRuntimeVariables(instance.getId())
                    : safeHistoricVariables(instance.getId()));
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    // Generic instance-start with caller-supplied business data (e.g. an order id/number) - each
    // process's own actual BPMN-declared variable names, not any fixed schema. Every deployed
    // generated controller's own /start endpoint only takes a business key (see
    // GeneratedManufacturingController/GeneratedTwinController), and this template does not mount
    // Camunda's own REST API - this is the smallest generic addition needed to demonstrate that
    // business data supplied at start really does flow through to the runtime the dashboard reads
    // from, for any process key this application has deployed, not just one.
    @PostMapping("/start/{processDefinitionKey}")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String processDefinitionKey,
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String businessKey = (String) body.get("businessKey");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) body.getOrDefault("variables", Map.of());
        ProcessInstance instance = (businessKey == null || businessKey.isBlank())
                ? runtimeService.startProcessInstanceByKey(processDefinitionKey, variables)
                : runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processInstanceId", instance.getId());
        result.put("businessKey", instance.getBusinessKey());
        // Same initiator/responder pairing every generated controller's own /start performs (see
        // GeneratedManufacturingController) - without this, a pair started through this generic
        // endpoint would fall back to SignalBroadcaster's unpaired-delivery path instead of a real
        // RabbitMQ REQUEST/RESPONSE handoff.
        String role = pairRegistry.registerAndClassify(instance.getBusinessKey(), instance.getId());
        if (role != null) {
            result.put("role", role);
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> safeRuntimeVariables(String processInstanceId) {
        try {
            return runtimeService.getVariables(processInstanceId);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> safeHistoricVariables(String processInstanceId) {
        // Requires camunda.bpm.history-level=full (the generated project's own default) - falls
        // back to an empty map rather than failing the whole response if history level is ever
        // lowered.
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).list()
                    .forEach(v -> variables.put(v.getName(), v.getValue()));
            return variables;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // Names of the meaningful flow nodes (tasks, events - not just sequence flows) in a deployed
    // process definition's own BPMN model, read back from what Camunda actually has stored for it -
    // no per-BPMN generation-time list, so this stays correct even if a process were ever
    // redeployed with a different shape.
    private List<String> activityNames(String processDefinitionId) {
        List<String> names = new ArrayList<>();
        try {
            BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinitionId);
            for (FlowNode node : model.getModelElementsByType(FlowNode.class)) {
                String name = node.getName();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            // Best-effort - an empty list is a fine fallback for a UI that just wants a hint of
            // what this process does, not a definitive activity inventory.
        }
        return names;
    }
}
