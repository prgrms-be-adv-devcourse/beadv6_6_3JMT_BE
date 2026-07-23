package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossRetryConfigTest {

    private final TossRetryConfig config = new TossRetryConfig();

    @Test
    void 설정값대로_confirm_Retry를_생성한다() {
        RetryRegistry registry = config.tossRetryRegistry(2, Duration.ofMillis(500));

        Retry confirmRetry = config.tossConfirmRetry(registry);

        assertThat(confirmRetry.getName()).isEqualTo("tossConfirmRetry");
        assertThat(confirmRetry.getRetryConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(confirmRetry.getRetryConfig().getIntervalBiFunction().apply(1, null)).isEqualTo(500L);
    }
}
