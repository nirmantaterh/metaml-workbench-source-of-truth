package com.example.camundademo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "camunda.bpm")
@Getter
@Setter
public class CamundaProcessProperties {
    private String processEngineName;
    private String deploymentResourcePattern;
    private boolean skipIsolationLevelCheck;
    private boolean enforceHistoryTimeToLive;
    private String databaseSchemaUpdate;
    private boolean jobExecutorActivate;
}
