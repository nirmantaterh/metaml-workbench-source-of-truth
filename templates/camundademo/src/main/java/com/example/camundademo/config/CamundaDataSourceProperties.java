package com.example.camundademo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.datasource.camunda")
@Getter
@Setter
public class CamundaDataSourceProperties {
    private String url;
    private String username;
    private String password;
    private String driverClassName;
}
