package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossCircuitBreakerConfig {

    @Bean
    public CircuitBreakerRegistry tossCircuitBreakerRegistry(
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.sliding-window-size}")
        int slidingWindowSize,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.minimum-number-of-calls}")
        int minimumNumberOfCalls,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.failure-rate-threshold}")
        float failureRateThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.slow-call-duration-threshold}")
        Duration slowCallDurationThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.slow-call-rate-threshold}")
        float slowCallRateThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.wait-duration-in-open-state}")
        Duration waitDurationInOpenState,
        @Value(
            "${resilience4j.circuitbreaker.configs.toss-payment-default.permitted-number-of-calls-in-half-open-state}"
        )
        int permittedNumberOfCallsInHalfOpenState
    ) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .slowCallDurationThreshold(slowCallDurationThreshold)
            .slowCallRateThreshold(slowCallRateThreshold)
            .waitDurationInOpenState(waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
            .recordException(new TossFailurePredicate())
            .build();
        return CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    @Bean("tossConfirmCircuitBreaker")
    public CircuitBreaker tossConfirmCircuitBreaker(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return tossCircuitBreakerRegistry.circuitBreaker("tossConfirmCircuitBreaker");
    }

    @Bean("tossRefundCircuitBreaker")
    public CircuitBreaker tossRefundCircuitBreaker(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return tossCircuitBreakerRegistry.circuitBreaker("tossRefundCircuitBreaker");
    }

    @Bean
    public MeterBinder tossCircuitBreakerMetrics(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(tossCircuitBreakerRegistry);
    }
}
