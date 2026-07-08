package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.event.SettlementCreatedMessage;
import com.prompthub.settlement.application.event.SettlementEventEnvelope;
import com.prompthub.settlement.global.exception.SettlementException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class KafkaSettlementEventPublisherTest {

    private static final String TOPIC = "settlement-events";

    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaSettlementEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaSettlementEventPublisher(kafkaTemplate, TOPIC);
    }

    private SettlementCreatedMessage sampleMessage(UUID settlementId) {
        return new SettlementCreatedMessage(
                settlementId, UUID.randomUUID(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, new BigDecimal("100.00"), new BigDecimal("85.00"),
                new BigDecimal("15.00"), BigDecimal.ZERO, LocalDateTime.of(2026, 7, 1, 4, 0));
    }

    @Test
    @DisplayName("payload를 envelope로 감싸 settlement-events 토픽에 settlementId 키로 발행한다")
    void publish_wrapsEnvelopeAndSends() {
        UUID settlementId = UUID.randomUUID();
        SettlementCreatedMessage message = sampleMessage(settlementId);
        given(kafkaTemplate.send(eq(TOPIC), eq(settlementId.toString()), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        publisher.publishSettlementCreated(message);

        ArgumentCaptor<SettlementEventEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(SettlementEventEnvelope.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(settlementId.toString()), envelopeCaptor.capture());
        SettlementEventEnvelope<?> envelope = envelopeCaptor.getValue();
        assertThat(envelope.eventType()).isEqualTo("settlement.created");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.aggregateId()).isEqualTo(settlementId);
        assertThat(envelope.payload()).isEqualTo(message);
    }

    @Test
    @DisplayName("발행 중 예외가 나면 SettlementException으로 감싼다")
    void publish_whenSendFails_throwsSettlementException() {
        UUID settlementId = UUID.randomUUID();
        SettlementCreatedMessage message = sampleMessage(settlementId);
        given(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .willThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> publisher.publishSettlementCreated(message))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("발행 후 비동기 전송이 실패해도 예외를 던지지 않고 로깅만 한다")
    void publish_whenAsyncSendFails_doesNotThrow() {
        UUID settlementId = UUID.randomUUID();
        SettlementCreatedMessage message = sampleMessage(settlementId);
        given(kafkaTemplate.send(eq(TOPIC), eq(settlementId.toString()), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatCode(() -> publisher.publishSettlementCreated(message))
                .doesNotThrowAnyException();
    }
}
