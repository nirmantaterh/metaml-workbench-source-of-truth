package com.example.camundademo.config;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.spring.ProcessEngineFactoryBean;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import javax.sql.DataSource;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableConfigurationProperties({ CamundaDataSourceProperties.class, CamundaProcessProperties.class })
@AllArgsConstructor
public class CamundaConfig {
    private final CamundaDataSourceProperties camundaDataSourceProperties;
    private final CamundaProcessProperties camundaProcessProperties;
    private static final Logger logger = LoggerFactory.getLogger(CamundaConfig.class);

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(camundaDataSourceProperties.getDriverClassName());
        dataSource.setUrl(camundaDataSourceProperties.getUrl());
        dataSource.setUsername(camundaDataSourceProperties.getUsername());
        dataSource.setPassword(camundaDataSourceProperties.getPassword());
        return dataSource;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public SpringProcessEngineConfiguration engineConfiguration(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            @Value("classpath*:*.bpmn") Resource[] deploymentResources) {
        SpringProcessEngineConfiguration configuration = new SpringProcessEngineConfiguration();

        configuration.setProcessEngineName(camundaProcessProperties.getProcessEngineName());
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate(camundaProcessProperties.getDatabaseSchemaUpdate());
        configuration.setJobExecutorActivate(camundaProcessProperties.isJobExecutorActivate());
        configuration.setDeploymentResources(deploymentResources);

        // Added configurations configuration.setHistory("full"); configuration.setSkipHistoryOptimisticLockingExceptions(true);
        configuration.setSkipIsolationLevelCheck(camundaProcessProperties.isSkipIsolationLevelCheck()); // Ensure this
                                                                                                        // property is set to true
        configuration.setEnforceHistoryTimeToLive(camundaProcessProperties.isEnforceHistoryTimeToLive());

        // Load BPMN files from the classpath
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(camundaProcessProperties.getDeploymentResourcePattern());
            configuration.setDeploymentResources(resources);
        } catch (IOException e) {
            logger.error("An error occurred loading process models: ", e);
            // e.printStackTrace();
        }
        return configuration;
    }

    @Bean
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcessEngine processEngine(ProcessEngineFactoryBean factoryBean) throws Exception {
        return factoryBean.getObject();
    }
}
