package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.application.usecase.OutboxEventUseCase;
import com.prompthub.settlement.domain.model.OutboxEvent;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.infrastructure.persistence.outbox.OutboxEventJpaRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.cloud.config.fail-fast=false"
})
@ActiveProfiles("test")
class OutboxRelayIntegrationTest {

    @Autowired
    private OutboxEventUseCase useCase;

    @Autowired
    private OutboxEventJpaRepository repository;

    @MockitoBean
    private SettlementEventPublisher publisher;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("다음 배치 시작 flush는 이전 실패 이벤트의 동일 JSON과 eventId를 재발행한다")
    void flushPendingBefore_retriesPreviousPendingEvent() {
        // given
        OutboxEvent event = event(1, LocalDateTime.now().minusHours(2));
        event.recordPublishFailure("previous failure", LocalDateTime.now().minusHours(1), 3);
        repository.saveAndFlush(event);

        // when
        useCase.flushPendingBefore(LocalDateTime.now());

        // then
        OutboxEvent reloaded = repository.findById(event.getEventId()).orElseThrow();
        then(publisher).should().publish(event.getTopic(), event.getAggregateId(), event.getPayload());
        assertThat(reloaded.getEventId()).isEqualTo(event.getEventId());
        assertThat(reloaded.getPayload()).isEqualTo(event.getPayload());
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("FAILED 이벤트 redrive는 정산 계산 없이 지정 이벤트만 새 주기로 즉시 발행한다")
    void redrive_failedEvent_publishesOnlyTarget() {
        // given
        OutboxEvent target = event(11, LocalDateTime.now().minusHours(2));
        target.recordPublishFailure("terminal", LocalDateTime.now().minusHours(1), 1);
        OutboxEvent untouched = event(12, LocalDateTime.now().minusHours(2));
        untouched.recordPublishFailure("terminal", LocalDateTime.now().minusHours(1), 1);
        repository.saveAndFlush(target);
        repository.saveAndFlush(untouched);

        // when
        useCase.redrive(target.getEventId());

        // then
        OutboxEvent redriven = repository.findById(target.getEventId()).orElseThrow();
        OutboxEvent notRedriven = repository.findById(untouched.getEventId()).orElseThrow();
        then(publisher).should().publish(target.getTopic(), target.getAggregateId(), target.getPayload());
        assertThat(redriven.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(redriven.getRetryCount()).isZero();
        assertThat(notRedriven.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    @DisplayName("한 이벤트 Kafka 실패를 기록하고도 다음 후보 발행을 계속한다")
    void flushBatch_kafkaFailure_continuesNextCandidate() {
        // given
        UUID batchId = UUID.randomUUID();
        OutboxEvent first = event(21, batchId, LocalDateTime.now().minusMinutes(2));
        OutboxEvent second = event(22, batchId, LocalDateTime.now().minusMinutes(1));
        repository.saveAndFlush(first);
        repository.saveAndFlush(second);
        willThrow(new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED))
                .willDoNothing()
                .given(publisher)
                .publish(anyString(), any(UUID.class), anyString());

        // when
        useCase.flushBatch(batchId);

        // then
        OutboxEvent failedOnce = repository.findById(first.getEventId()).orElseThrow();
        OutboxEvent published = repository.findById(second.getEventId()).orElseThrow();
        assertThat(failedOnce.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failedOnce.getRetryCount()).isEqualTo(1);
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        then(publisher).should(org.mockito.Mockito.times(2))
                .publish(anyString(), any(UUID.class), anyString());
    }

    private OutboxEvent event(long suffix, LocalDateTime occurredAt) {
        return event(suffix, UUID.randomUUID(), occurredAt);
    }

    private OutboxEvent event(long suffix, UUID batchId, LocalDateTime occurredAt) {
        UUID eventId = new UUID(0L, suffix);
        return OutboxEvent.create(
                eventId,
                batchId,
                "SETTLEMENT",
                UUID.randomUUID(),
                "SETTLEMENT_CREATED",
                "settlement-events",
                "{\"eventId\":\"" + eventId + "\"}",
                occurredAt);
    }
}
