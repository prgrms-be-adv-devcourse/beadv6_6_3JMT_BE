package com.prompthub.ai.global.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AiAgentConfig {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock aiClock() {
        return Clock.system(SERVICE_ZONE);
    }

    @Bean
    @ConditionalOnMissingBean(TokenCountEstimator.class)
    public TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean(name = "aiProviderCallExecutor", destroyMethod = "shutdownNow")
    public ExecutorService aiProviderCallExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-provider-call-", 0).factory());
    }
}
