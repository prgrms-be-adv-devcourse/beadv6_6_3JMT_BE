package com.prompthub.order.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.prompthub.order.domain.enums.OutboxEventStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OutboxRedriveHistoryTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 11, 0);
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 5, 12, 0);
    private static final OutboxRetryPolicy POLICY = new OutboxRetryPolicy(
        Duration.ofSeconds(1), Duration.ofMinutes(1), 10
    );

    @Test
    void createCopiesFailureEvidenceBeforeManualRedrive() {
        OutboxEvent failed = failedEvent();

        OutboxRedriveHistory history = OutboxRedriveHistory.create(
            failed, OPERATOR_ID, "Kafka 장애 복구 확인", REQUESTED_AT
        );

        assertThat(history.getEventId()).isEqualTo(failed.getEventId());
        assertThat(history.getRequestedBy()).isEqualTo(OPERATOR_ID);
        assertThat(history.getPreviousRetryCount()).isEqualTo(10);
        assertThat(history.getPreviousLastAttemptAt()).isEqualTo(failed.getLastAttemptAt());
        assertThat(history.getPreviousLastError()).isEqualTo(failed.getLastError());
    }

    @Test
    void createRejectsInvalidReason() {
        OutboxEvent failed = failedEvent();

        assertThatIllegalArgumentException().isThrownBy(() -> OutboxRedriveHistory.create(
            failed, OPERATOR_ID, "a".repeat(501), REQUESTED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> OutboxRedriveHistory.create(
            failed, OPERATOR_ID, "  ", REQUESTED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> OutboxRedriveHistory.create(
            failed, OPERATOR_ID, null, REQUESTED_AT
        ));
    }

    @Test
    void createCountsSupplementaryCharactersByCodePoint() {
        OutboxEvent failed = failedEvent();
        String emoji = "\uD83D\uDE00";

        OutboxRedriveHistory history = OutboxRedriveHistory.create(
            failed, OPERATOR_ID, emoji.repeat(500), REQUESTED_AT
        );

        assertThat(history.getReason()).hasSize(1_000);
        assertThatIllegalArgumentException().isThrownBy(() -> OutboxRedriveHistory.create(
            failed, OPERATOR_ID, emoji.repeat(501), REQUESTED_AT
        ));
    }

    private OutboxEvent failedEvent() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID, AGGREGATE_ID, "ORDER_PAID", "{}", OCCURRED_AT
        );
        for (int attempt = 1; attempt <= 10; attempt++) {
            event.recordPublishFailure(
                OCCURRED_AT.plusSeconds(attempt), "Kafka unavailable", POLICY
            );
        }
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        return event;
    }
}
