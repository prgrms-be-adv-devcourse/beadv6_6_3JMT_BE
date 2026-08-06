package com.prompthub.order.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryPolicyTest {

    private static final LocalDateTime ATTEMPTED_AT = LocalDateTime.of(2026, 8, 5, 10, 0);

    private final OutboxRetryPolicy policy = new OutboxRetryPolicy(
        Duration.ofSeconds(5), Duration.ofMinutes(5), 10
    );

    @Test
    void firstFailureUsesInitialDelay() {
        assertThat(policy.nextAttemptAt(ATTEMPTED_AT, 1))
            .isEqualTo(ATTEMPTED_AT.plusSeconds(5));
    }

    @Test
    void backoffDoublesAndStopsAtMaximumDelay() {
        assertThat(policy.nextAttemptAt(ATTEMPTED_AT, 4))
            .isEqualTo(ATTEMPTED_AT.plusSeconds(40));
        assertThat(policy.nextAttemptAt(ATTEMPTED_AT, 10))
            .isEqualTo(ATTEMPTED_AT.plusMinutes(5));
    }
}
