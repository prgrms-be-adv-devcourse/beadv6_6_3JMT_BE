package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventPayloadMapper eventPayloadMapper = new EventPayloadMapper(objectMapper);

    @Mock
    private PaymentApprovedProcessor processor;

    private PaymentApprovedEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentApprovedEventHandler(eventPayloadMapper, processor);
    }

    @Test
    @DisplayName("이벤트를 올바른 페이로드로 변환하여 프로세서로 위임한다")
    void handle_delegatesToProcessor() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        UUID paymentId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        String payloadJson = """
            {
                "paymentId": "%s",
                "orderId": "%s",
                "buyerId": "%s",
                "pgTxId": "tx123",
                "paymentMethod": "CARD",
                "provider": "TOSS",
                "approvedAmount": 30000,
                "approvedAt": "2026-06-19T12:00:00"
            }
        """.formatted(paymentId, aggregateId, buyerId);

        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        EventMessage<JsonNode> message = new EventMessage<>(
            eventId,
            "PAYMENT_APPROVED",
            occurredAt,
            "PAYMENT",
            aggregateId,
            payloadNode
        );

        handler.handle(message);

        ArgumentCaptor<PaymentApprovedPayload> captor = ArgumentCaptor.forClass(PaymentApprovedPayload.class);
        then(processor).should().process(eq(eventId), eq("PAYMENT_APPROVED"), eq(occurredAt), captor.capture());

        PaymentApprovedPayload payload = captor.getValue();
        assertThat(payload.paymentId()).isEqualTo(paymentId);
        assertThat(payload.orderId()).isEqualTo(aggregateId);
        assertThat(payload.buyerId()).isEqualTo(buyerId);
        assertThat(payload.approvedAmount()).isEqualTo(30000);
    }

    @Test
    @DisplayName("필수 필드 누락 시 파싱 예외가 발생한다")
    void handle_missingFields_throwsException() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();

        String payloadJson = """
            {
                "orderId": "%s"
            }
        """.formatted(aggregateId);

        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        EventMessage<JsonNode> message = new EventMessage<>(
            eventId,
            "PAYMENT_APPROVED",
            occurredAt,
            "PAYMENT",
            aggregateId,
            payloadNode
        );

        assertThatThrownBy(() -> handler.handle(message))
            .isInstanceOf(OrderException.class);

        then(processor).should(never()).process(any(), any(), any(), any());
    }
}
