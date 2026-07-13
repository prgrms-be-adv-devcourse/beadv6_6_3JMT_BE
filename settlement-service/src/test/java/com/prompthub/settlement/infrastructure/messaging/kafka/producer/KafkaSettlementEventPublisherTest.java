package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.global.exception.SettlementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class KafkaSettlementEventPublisherTest {

    private static final String TOPIC = "settlement-events";
    private static final UUID AGGREGATE_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String PAYLOAD = "{\"eventId\":\"stable-event-id\"}";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("저장된 JSON을 정확한 topic과 aggregate key로 보내고 broker ack를 기다린다")
    void publish_acknowledged_sendsExactRawJson() {
        // given
        given(kafkaTemplate.send(TOPIC, AGGREGATE_ID.toString(), PAYLOAD))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaSettlementEventPublisher publisher = publisher(10_000L);

        // when
        assertThatCode(() -> publisher.publish(TOPIC, AGGREGATE_ID, PAYLOAD))
                .doesNotThrowAnyException();

        // then
        then(kafkaTemplate).should().send(TOPIC, AGGREGATE_ID.toString(), PAYLOAD);
    }

    @Test
    @DisplayName("broker가 전송 실패를 응답하면 SettlementException을 던진다")
    void publish_failedFuture_throwsSettlementException() {
        // given
        given(kafkaTemplate.send(TOPIC, AGGREGATE_ID.toString(), PAYLOAD))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        // when & then
        assertThatThrownBy(() -> publisher(10_000L).publish(TOPIC, AGGREGATE_ID, PAYLOAD))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("broker ack가 제한 시간 안에 오지 않으면 SettlementException을 던진다")
    void publish_ackTimeout_throwsSettlementException() {
        // given
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        given(kafkaTemplate.send(TOPIC, AGGREGATE_ID.toString(), PAYLOAD)).willReturn(pending);

        // when & then
        assertThatThrownBy(() -> publisher(1L).publish(TOPIC, AGGREGATE_ID, PAYLOAD))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("broker ack 대기 중 interrupt되면 플래그를 복구하고 SettlementException을 던진다")
    void publish_interrupted_restoresInterruptFlag() throws Exception {
        // given
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(kafkaTemplate.send(TOPIC, AGGREGATE_ID.toString(), PAYLOAD)).willReturn(future);
        given(future.get(10_000L, TimeUnit.MILLISECONDS))
                .willThrow(new InterruptedException("shutdown"));

        // when & then
        assertThatThrownBy(() -> publisher(10_000L).publish(TOPIC, AGGREGATE_ID, PAYLOAD))
                .isInstanceOf(SettlementException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private KafkaSettlementEventPublisher publisher(long publishTimeoutMs) {
        return new KafkaSettlementEventPublisher(kafkaTemplate, publishTimeoutMs);
    }
}
