package com.prompthub.order.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

public record OutboxRetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts) {

    public OutboxRetryPolicy {
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    public LocalDateTime nextAttemptAt(LocalDateTime attemptedAt, int failureCount) {
        if (failureCount <= 0) {
            throw new IllegalArgumentException("failureCount must be positive");
        }

        int exponent = Math.min(failureCount - 1, 62);
        Duration calculated;
        try {
            calculated = initialDelay.multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            calculated = maxDelay;
        }

        Duration delay = calculated.compareTo(maxDelay) > 0 ? maxDelay : calculated;
        return attemptedAt.plus(delay);
    }
}
