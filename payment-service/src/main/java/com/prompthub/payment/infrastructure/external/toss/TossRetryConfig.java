package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossRetryConfig {

    @Bean
    public RetryRegistry tossRetryRegistry(
        @Value("${resilience4j.retry.instances.toss-confirm-retry.max-attempts}") int maxAttempts,
        @Value("${resilience4j.retry.instances.toss-confirm-retry.wait-duration}") Duration waitDuration
    ) {
        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .waitDuration(waitDuration)
            .retryOnException(new TossRetryPredicate())
            .build();
        return RetryRegistry.of(retryConfig);
    }

    @Bean("tossConfirmRetry")
    public Retry tossConfirmRetry(RetryRegistry tossRetryRegistry) {
        return tossRetryRegistry.retry("tossConfirmRetry");
    }

    @Bean
    public MeterBinder tossRetryMetrics(RetryRegistry tossRetryRegistry) {
        return TaggedRetryMetrics.ofRetryRegistry(tossRetryRegistry);
    }
}
