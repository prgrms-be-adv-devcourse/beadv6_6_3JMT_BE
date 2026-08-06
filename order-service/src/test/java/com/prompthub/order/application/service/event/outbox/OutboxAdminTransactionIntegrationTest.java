package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRedriveHistory;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import com.prompthub.order.domain.repository.OutboxRedriveHistoryRepository;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxRedriveHistoryPersistence;
import com.prompthub.order.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

class OutboxAdminTransactionIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000711");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000712");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);
    private static final LocalDateTime LAST_ATTEMPT_AT = LocalDateTime.of(2026, 8, 5, 10, 31);
    private static final String LAST_ERROR = "Kafka broker unavailable";

    @Autowired
    private OutboxAdminApplicationService service;

    @Autowired
    private OutboxEventPersistence outboxEventPersistence;

    @Autowired
    private OutboxRedriveHistoryPersistence outboxRedriveHistoryPersistence;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private OrderExpirationStore orderExpirationStore;

    @MockitoBean
    private OutboxMetrics outboxMetrics;

    @MockitoSpyBean
    private OutboxRedriveHistoryRepository outboxRedriveHistoryRepository;

    @AfterEach
    void resetSpy() {
        reset(outboxRedriveHistoryRepository);
    }

    @Test
    void redrive_persistsAuditEvidenceAndResetsFailedEventInOneTransaction() {
        outboxEventPersistence.saveAndFlush(failedEvent());

        OutboxRedriveResult result = service.redrive(EVENT_ID, ADMIN_ID, "Kafka 복구 확인");

        assertThat(result.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(result.nextAttemptAt()).isNotNull();
        OutboxEvent event = outboxEventPersistence.findById(EVENT_ID).orElseThrow();
        assertThat(event).satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(saved.getRetryCount()).isZero();
            assertThat(saved.getNextAttemptAt())
                .isEqualTo(result.nextAttemptAt().truncatedTo(ChronoUnit.MICROS));
            assertThat(saved.getLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
            assertThat(saved.getLastError()).isEqualTo(LAST_ERROR);
        });
        assertThat(outboxRedriveHistoryPersistence.findAll()).singleElement().satisfies(history -> {
            assertThat(history.getEventId()).isEqualTo(EVENT_ID);
            assertThat(history.getRequestedBy()).isEqualTo(ADMIN_ID);
            assertThat(history.getReason()).isEqualTo("Kafka 복구 확인");
            assertThat(history.getPreviousRetryCount()).isEqualTo(1);
            assertThat(history.getPreviousLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
            assertThat(history.getPreviousLastError()).isEqualTo(LAST_ERROR);
        });
    }

    @Test
    void redrive_historyFlushFailureRollsBackEventStateAndDoesNotPersistAudit() {
        outboxEventPersistence.saveAndFlush(failedEvent());
        willThrow(new RuntimeException("history persistence failure"))
            .given(outboxRedriveHistoryRepository).saveAndFlush(any(OutboxRedriveHistory.class));

        assertThatThrownBy(() -> service.redrive(EVENT_ID, ADMIN_ID, "Kafka 복구 확인"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("history persistence failure");

        OutboxEvent event = outboxEventPersistence.findById(EVENT_ID).orElseThrow();
        assertThat(event).satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getNextAttemptAt()).isNull();
            assertThat(saved.getLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
            assertThat(saved.getLastError()).isEqualTo(LAST_ERROR);
        });
        assertThat(outboxRedriveHistoryPersistence.count()).isZero();
    }

    private OutboxEvent failedEvent() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000713"),
            "ORDER_PAID",
            "{\"eventType\":\"ORDER_PAID\"}",
            OCCURRED_AT
        );
        event.recordPublishFailure(
            LAST_ATTEMPT_AT,
            LAST_ERROR,
            new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1)
        );
        return event;
    }
}
