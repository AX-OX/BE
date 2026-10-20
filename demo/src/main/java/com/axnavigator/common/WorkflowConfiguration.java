package com.axnavigator.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class WorkflowConfiguration {
    @Bean(destroyMethod = "close")
    ExecutorService agentExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }
}
