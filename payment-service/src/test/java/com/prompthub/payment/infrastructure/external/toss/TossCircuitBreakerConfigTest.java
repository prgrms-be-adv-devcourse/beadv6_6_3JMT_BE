package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossCircuitBreakerConfigTest {

    private final TossCircuitBreakerConfig config = new TossCircuitBreakerConfig();

    @Test
    void 설정값대로_confirm과_refund_CircuitBreaker를_각각_생성한다() {
        CircuitBreakerRegistry registry = config.tossCircuitBreakerRegistry(
            20, 10, 50f, Duration.ofMillis(20_000), 50f, Duration.ofSeconds(30), 3
        );

        CircuitBreaker confirmCircuitBreaker = config.tossConfirmCircuitBreaker(registry);
        CircuitBreaker refundCircuitBreaker = config.tossRefundCircuitBreaker(registry);

        assertThat(confirmCircuitBreaker.getName()).isEqualTo("tossConfirmCircuitBreaker");
        assertThat(refundCircuitBreaker.getName()).isEqualTo("tossRefundCircuitBreaker");
        assertThat(confirmCircuitBreaker).isNotSameAs(refundCircuitBreaker);

        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getSlowCallDurationThreshold())
            .isEqualTo(Duration.ofMillis(20_000));
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
            .isEqualTo(30_000L);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(3);
    }
}
