package com.tp.TargetPlatform.proxy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proxy")
public class ProxyProcessController {

    @GetMapping("/health")
    public String health() {
        return "proxy ok";
    }
    // TODO: endpoints to start / query proxy process instances
}
