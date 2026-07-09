package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.prompthub.common.event.EventMessage;
import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import com.prompthub.settlement.global.exception.SettlementException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class KafkaSettlementEventPublisherTest {

    private static final String TOPIC = "settlement-events";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaSettlementEventPublisher publisher() {
        return new KafkaSettlementEventPublisher(kafkaTemplate, TOPIC);
    }

    private SettlementCreatedPayload payload(java.util.UUID settlementId) {
        return new SettlementCreatedPayload(settlementId, java.util.UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 2,
                new BigDecimal("300.00"), new BigDecimal("255.00"), new BigDecimal("45.00"),
                BigDecimal.ZERO, LocalDateTime.of(2026, 6, 24, 3, 0));
    }

    @Test
    @DisplayName("공통 EventMessage로 감싸 settlement-events 토픽에 발행한다 — eventType·aggregateType·aggregateId·key")
    @SuppressWarnings("unchecked")
    void publish_wrapsInEventMessageAndSends() {
        // given
        java.util.UUID settlementId = java.util.UUID.randomUUID();
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher().publishSettlementCreated(payload(settlementId));

        // then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
                .send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo(settlementId.toString());

        EventMessage<SettlementCreatedPayload> message =
                (EventMessage<SettlementCreatedPayload>) valueCaptor.getValue();
        assertThat(message.eventId()).isNotNull();
        assertThat(message.eventType()).isEqualTo("SETTLEMENT_CREATED");
        assertThat(message.aggregateType()).isEqualTo("SETTLEMENT");
        assertThat(message.aggregateId()).isEqualTo(settlementId);
        assertThat(message.occurredAt()).isNotNull();
        assertThat(message.payload().settlementId()).isEqualTo(settlementId);
    }

    @Test
    @DisplayName("동기 전송 실패(직렬화·설정 오류 등)는 SettlementException으로 변환해 던진다")
    void publish_syncFailure_throwsSettlementException() {
        // given
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenThrow(new RuntimeException("serialize error"));

        // when & then
        assertThatThrownBy(() -> publisher().publishSettlementCreated(payload(java.util.UUID.randomUUID())))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("비동기 전송 실패(broker down 등)는 로깅만 하고 예외를 전파하지 않는다 (at-most-once)")
    void publish_asyncFailure_doesNotThrow() {
        // given
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // when & then
        assertThatCode(() -> publisher().publishSettlementCreated(payload(java.util.UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
