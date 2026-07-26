package com.prompthub.ai.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AiExecutionConfig {

    @Bean(name = "aiSettlementExecutor")
    public ThreadPoolTaskExecutor aiSettlementExecutor(AiSettlementProperties properties) {
        int maxConcurrentRuns = properties.execution().maxConcurrentRuns();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentRuns);
        executor.setMaxPoolSize(maxConcurrentRuns);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ai-settlement-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
