package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.domain.model.OutboxEvent;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventPublishServiceTest {

    private OutboxEventRepository repository;
    private SettlementEventPublisher publisher;
    private OutboxEventPublishService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        publisher = mock(SettlementEventPublisher.class);
        service = new OutboxEventPublishService(repository, publisher, 3);
    }

    @Test
    @DisplayName("PENDING 이벤트 발행 성공 시 저장된 원문을 보내고 PUBLISHED로 변경한다")
    void publish_pendingEvent_marksPublished() {
        // given
        OutboxEvent event = pendingEvent();
        given(repository.findById(event.getEventId())).willReturn(Optional.of(event));

        // when
        service.publish(event.getEventId());

        // then
        then(publisher).should().publish(event.getTopic(), event.getAggregateId(), event.getPayload());
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastAttemptedAt()).isEqualTo(event.getPublishedAt());
    }

    @Test
    @DisplayName("첫 발행 실패는 예외를 삼키고 PENDING 1회 실패로 기록한다")
    void publish_firstKafkaFailure_recordsPendingFailure() {
        // given
        OutboxEvent event = pendingEvent();
        given(repository.findById(event.getEventId())).willReturn(Optional.of(event));
        willThrow(new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED))
                .given(publisher)
                .publish(event.getTopic(), event.getAggregateId(), event.getPayload());

        // when
        service.publish(event.getEventId());

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastAttemptedAt()).isNotNull();
        assertThat(event.getLastFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("세 번째 발행 실패는 FAILED로 기록한다")
    void publish_thirdKafkaFailure_marksFailed() {
        // given
        OutboxEvent event = pendingEvent();
        event.recordPublishFailure("first", LocalDateTime.now().minusMinutes(2), 3);
        event.recordPublishFailure("second", LocalDateTime.now().minusMinutes(1), 3);
        given(repository.findById(event.getEventId())).willReturn(Optional.of(event));
        willThrow(new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED))
                .given(publisher)
                .publish(event.getTopic(), event.getAggregateId(), event.getPayload());

        // when
        service.publish(event.getEventId());

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("후보 조회 후 이미 처리된 이벤트는 중복 발행하지 않는다")
    void publish_alreadyPublished_skips() {
        // given
        OutboxEvent event = pendingEvent();
        event.markPublished(LocalDateTime.now());
        given(repository.findById(event.getEventId())).willReturn(Optional.of(event));

        // when
        service.publish(event.getEventId());

        // then
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("존재하지 않는 이벤트 발행 요청은 OUTBOX_EVENT_NOT_FOUND 예외를 던진다")
    void publish_missingEvent_throwsNotFound() {
        // given
        UUID eventId = UUID.randomUUID();
        given(repository.findById(eventId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.publish(eventId))
                .isInstanceOf(SettlementException.class)
                .extracting(exception -> ((SettlementException) exception).getErrorCode())
                .isEqualTo(SettlementErrorCode.OUTBOX_EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("FAILED 이벤트 redrive는 실패 정보를 초기화하고 동일 원문을 즉시 발행한다")
    void redrive_failedEvent_requeuesAndPublishes() {
        // given
        OutboxEvent event = pendingEvent();
        event.recordPublishFailure("terminal", LocalDateTime.now().minusMinutes(1), 1);
        given(repository.findById(event.getEventId())).willReturn(Optional.of(event));

        // when
        service.redrive(event.getEventId());

        // then
        then(publisher).should().publish(event.getTopic(), event.getAggregateId(), event.getPayload());
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getFailedAt()).isNull();
        assertThat(event.getLastFailureReason()).isNull();
    }

    private OutboxEvent pendingEvent() {
        UUID eventId = UUID.randomUUID();
        return OutboxEvent.create(
                eventId,
                UUID.randomUUID(),
                "SETTLEMENT",
                UUID.randomUUID(),
                "SETTLEMENT_CREATED",
                "settlement-events",
                "{\"eventId\":\"" + eventId + "\"}",
                LocalDateTime.now().minusMinutes(10));
    }
}
