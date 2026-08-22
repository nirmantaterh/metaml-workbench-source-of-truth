package com.tp.TargetPlatform.twin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/twin")
public class TwinProcessController {

    @GetMapping("/health")
    public String health() {
        return "twin ok";
    }
    // TODO: endpoints to start / query twin process instances
}
