package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;

final class TossRetryTestSupport {

    private TossRetryTestSupport() {
    }

    static Retry retryOf(String name) {
        return Retry.of(name, RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(500))
            .retryOnException(new TossRetryPredicate())
            .build());
    }
}
