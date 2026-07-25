package com.metaml.nodemanager.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metaml.nodemanager.payload.AgentAvailabilityResponse;
import com.metaml.nodemanager.service.NodeManagerService;

@RestController
@RequestMapping("/api/v1/node-manager")
@RequiredArgsConstructor
public class NodeManagerController {

    private final NodeManagerService nodeManagerService;

    @GetMapping("/agents/{agentType}")
    public ResponseEntity<AgentAvailabilityResponse> getAgentAvailability(@PathVariable String agentType) {
        return ResponseEntity.ok(nodeManagerService.checkAvailability(agentType));
    }
}
