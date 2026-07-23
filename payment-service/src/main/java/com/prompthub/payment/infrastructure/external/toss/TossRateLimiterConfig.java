package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossRateLimiterConfig {

    @Bean
    public RateLimiterRegistry tossRateLimiterRegistry(
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.limit-for-period}")
        int limitForPeriod,
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.limit-refresh-period}")
        Duration limitRefreshPeriod,
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.timeout-duration}")
        Duration timeoutDuration
    ) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(limitForPeriod)
            .limitRefreshPeriod(limitRefreshPeriod)
            .timeoutDuration(timeoutDuration)
            .build();
        return RateLimiterRegistry.of(rateLimiterConfig);
    }

    @Bean("tossConfirmRateLimiter")
    public RateLimiter tossConfirmRateLimiter(RateLimiterRegistry tossRateLimiterRegistry) {
        return tossRateLimiterRegistry.rateLimiter("tossConfirmRateLimiter");
    }

    @Bean
    public MeterBinder tossRateLimiterMetrics(RateLimiterRegistry tossRateLimiterRegistry) {
        return TaggedRateLimiterMetrics.ofRateLimiterRegistry(tossRateLimiterRegistry);
    }
}
