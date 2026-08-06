package com.prompthub.order.application.service.event.outbox;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RedriveOutcome;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRedriveHistory;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.domain.repository.OutboxRedriveHistoryRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class OutboxAdminApplicationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);
    private static final LocalDateTime LAST_ATTEMPT_AT = LocalDateTime.of(2026, 8, 5, 10, 31);
    private static final String REDRIVE_REASON = "Kafka 복구 확인";
    private static final String LAST_ERROR = "Kafka broker unavailable";
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-05T01:32:00Z"), ZoneOffset.UTC
    );

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxRedriveHistoryRepository outboxRedriveHistoryRepository;

    @Mock
    private OutboxMetrics outboxMetrics;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    @Captor
    private ArgumentCaptor<OutboxRedriveHistory> historyCaptor;

    private OutboxAdminApplicationService service;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        service = new OutboxAdminApplicationService(
            outboxEventRepository,
            outboxRedriveHistoryRepository,
            outboxMetrics,
            CLOCK
        );
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(OutboxAdminApplicationService.class);
            logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void getFailedEvents_convertsOneBasedPageAndExcludesPayloadFromSummary() {
        OutboxEvent failedEvent = failedEvent();
        Page<OutboxEvent> failedPage = new PageImpl<>(List.of(failedEvent));
        given(outboxEventRepository.findFailed(pageableCaptor.capture())).willReturn(failedPage);

        Page<OutboxEventSummary> result = service.getFailedEvents(2, 20);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.eventId()).isEqualTo(EVENT_ID);
            assertThat(summary.aggregateId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000703"));
            assertThat(summary.eventType()).isEqualTo("ORDER_PAID");
            assertThat(summary.status()).isEqualTo(OutboxEventStatus.FAILED);
            assertThat(summary.retryCount()).isEqualTo(1);
            assertThat(summary.occurredAt()).isEqualTo(OCCURRED_AT);
            assertThat(summary.lastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
            assertThat(summary.lastError()).isEqualTo(LAST_ERROR);
        });
        assertThat(OutboxEventSummary.class.getRecordComponents())
            .extracting(component -> component.getName())
            .doesNotContain("payload");
    }

    @Test
    void redrive_failedEventResetsForPublishAndAuditsPriorEvidence() {
        OutboxEvent event = failedEvent();
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));
        logAppender = startLogCapture();

        OutboxRedriveResult result = service.redrive(EVENT_ID, ADMIN_ID, REDRIVE_REASON);

        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(result.nextAttemptAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(event.getLeaseOwner()).isNull();
        assertThat(event.getLeaseUntil()).isNull();
        assertThat(event.getLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
        assertThat(event.getLastError()).isEqualTo(LAST_ERROR);
        then(outboxRedriveHistoryRepository).should().saveAndFlush(historyCaptor.capture());
        assertThat(historyCaptor.getValue()).satisfies(history -> {
            assertThat(history.getEventId()).isEqualTo(EVENT_ID);
            assertThat(history.getRequestedBy()).isEqualTo(ADMIN_ID);
            assertThat(history.getReason()).isEqualTo(REDRIVE_REASON);
            assertThat(history.getPreviousRetryCount()).isEqualTo(1);
            assertThat(history.getPreviousLastAttemptAt()).isEqualTo(LAST_ATTEMPT_AT);
            assertThat(history.getPreviousLastError()).isEqualTo(LAST_ERROR);
        });
        then(outboxMetrics).should().recordRedrive(RedriveOutcome.SUCCESS);
        assertStructuredLog("outcome=success", "errorCode=none", false);
    }

    @Test
    void redrive_missingEventRecordsFailureWithoutPersistingHistory() {
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.empty());
        logAppender = startLogCapture();

        assertThatThrownBy(() -> service.redrive(EVENT_ID, ADMIN_ID, REDRIVE_REASON))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OUTBOX_EVENT_NOT_FOUND);

        then(outboxRedriveHistoryRepository).shouldHaveNoInteractions();
        then(outboxMetrics).should().recordRedrive(RedriveOutcome.FAILURE);
        assertStructuredLog("outcome=failure", "errorCode=O020", true);
    }

    @Test
    void redrive_nonFailedEventDoesNotMutateOrPersistHistory() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000703"),
            "ORDER_PAID",
            "{\"sensitive\":\"payload\"}",
            OCCURRED_AT
        );
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));
        logAppender = startLogCapture();

        assertThatThrownBy(() -> service.redrive(EVENT_ID, ADMIN_ID, REDRIVE_REASON))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OUTBOX_REDRIVE_NOT_ALLOWED);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(OCCURRED_AT);
        then(outboxRedriveHistoryRepository).shouldHaveNoInteractions();
        then(outboxMetrics).should().recordRedrive(RedriveOutcome.FAILURE);
        assertStructuredLog("outcome=failure", "errorCode=O021", true);
    }

    @Test
    void redrive_metricFailureDoesNotPreventAcceptedBusinessState() {
        OutboxEvent event = failedEvent();
        given(outboxEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));
        willThrow(new IllegalStateException("metrics unavailable\nsecret=ignored"))
            .given(outboxMetrics).recordRedrive(RedriveOutcome.SUCCESS);
        logAppender = startLogCapture();

        OutboxRedriveResult result = service.redrive(EVENT_ID, ADMIN_ID, REDRIVE_REASON);

        assertThat(result.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        then(outboxRedriveHistoryRepository).should().saveAndFlush(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(logAppender.list)
            .filteredOn(eventLog -> eventLog.getFormattedMessage().contains("Outbox redrive metric recording failed."))
            .singleElement()
            .satisfies(eventLog -> {
                assertThat(eventLog.getFormattedMessage()).contains("outcome=SUCCESS").doesNotContain("secret=ignored");
                assertThat(eventLog.getThrowableProxy()).isNull();
            });
    }

    private OutboxEvent failedEvent() {
        OutboxEvent event = OutboxEvent.create(
            EVENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000703"),
            "ORDER_PAID",
            "{\"sensitive\":\"payload\"}",
            OCCURRED_AT
        );
        event.recordPublishFailure(
            LAST_ATTEMPT_AT,
            LAST_ERROR,
            new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1)
        );
        return event;
    }

    private ListAppender<ILoggingEvent> startLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxAdminApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void assertStructuredLog(String outcome, String errorCode, boolean expectedRejection) {
        assertThat(logAppender.list).anySatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message).contains("eventId=" + EVENT_ID, outcome, errorCode);
            if (expectedRejection) {
                assertThat(message).doesNotContain(ADMIN_ID.toString(), REDRIVE_REASON, "payload");
                assertThat(event.getThrowableProxy()).isNull();
            }
        });
    }
}
