package com.tp.TargetPlatform.worker;

import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

// Turns on @Scheduled generically for the whole generated platform (ExternalTaskPoller, SignalBroadcaster when present, and any future scheduled component), with a small configurable thread pool rather than Spring Boot's single-thread scheduler default.
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler(
            @Value("${metaml.scheduling.pool-size:4}") int poolSize) {
        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(poolSize));
    }
}
