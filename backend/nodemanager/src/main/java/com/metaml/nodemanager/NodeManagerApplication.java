package com.metaml.nodemanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.metaml.nodemanager.config.NodeManagerProperties;

@SpringBootApplication
@EnableConfigurationProperties(NodeManagerProperties.class)
public class NodeManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NodeManagerApplication.class, args);
    }

}
