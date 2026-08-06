package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RedriveOutcome;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.exception.OutboxEventInvalidStateException;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxRedriveApplicationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);
    private static final LocalDateTime LAST_ATTEMPT_AT = LocalDateTime.of(2026, 8, 5, 10, 31);
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-05T01:32:00Z"), ZoneOffset.UTC
    );

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxMetrics outboxMetrics;

    private OutboxRedriveApplicationService service;

    @BeforeEach
    void setUp() {
        service = new OutboxRedriveApplicationService(outboxEventRepository, outboxMetrics, CLOCK);
    }

    @Test
    void redrive_failedEventResetsRetryStateAndPreservesFailureCause() {
        OutboxEvent event = failedEvent();
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));

        service.redrive(EVENT_ID);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(event.getLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
        assertThat(event.getLastError()).isEqualTo("Kafka broker unavailable");
        then(outboxMetrics).should().recordRedrive(RedriveOutcome.SUCCESS);
    }

    @Test
    void redrive_nonFailedEventDoesNotChangeStateAndRecordsFailureMetric() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000702"),
            "ORDER_PAID",
            "{\"eventType\":\"ORDER_PAID\"}",
            OCCURRED_AT
        );
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));

        assertThatThrownBy(() -> service.redrive(EVENT_ID))
            .isInstanceOf(OutboxEventInvalidStateException.class);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        then(outboxMetrics).should().recordRedrive(RedriveOutcome.FAILURE);
    }

    private OutboxEvent failedEvent() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000702"),
            "ORDER_PAID",
            "{\"eventType\":\"ORDER_PAID\"}",
            OCCURRED_AT
        );
        event.recordPublishFailure(
            LAST_ATTEMPT_AT,
            "Kafka broker unavailable",
            new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1)
        );
        return event;
    }
}
