package com.tp.TargetPlatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "camunda.process")
public class CamundaProcessProperties {

    private String definitionKey;
    private boolean autoDeploy = true;

    public String getDefinitionKey() { return definitionKey; }
    public void setDefinitionKey(String definitionKey) { this.definitionKey = definitionKey; }

    public boolean isAutoDeploy() { return autoDeploy; }
    public void setAutoDeploy(boolean autoDeploy) { this.autoDeploy = autoDeploy; }
}
