package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossRateLimiterConfigTest {

    private final TossRateLimiterConfig config = new TossRateLimiterConfig();

    @Test
    void 설정값대로_confirm_RateLimiter를_생성한다() {
        RateLimiterRegistry registry = config.tossRateLimiterRegistry(30, Duration.ofSeconds(1), Duration.ZERO);

        RateLimiter confirmRateLimiter = config.tossConfirmRateLimiter(registry);

        assertThat(confirmRateLimiter.getName()).isEqualTo("tossConfirmRateLimiter");
        assertThat(confirmRateLimiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(30);
        assertThat(confirmRateLimiter.getRateLimiterConfig().getLimitRefreshPeriod())
            .isEqualTo(Duration.ofSeconds(1));
        assertThat(confirmRateLimiter.getRateLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ZERO);
    }
}
