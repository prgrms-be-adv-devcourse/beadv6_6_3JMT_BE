package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.global.exception.OrderException;
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
class PaymentRefundedEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventPayloadMapper eventPayloadMapper = new EventPayloadMapper(objectMapper);

    @Mock
    private PaymentRefundedProcessor processor;

    private PaymentRefundedEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentRefundedEventHandler(eventPayloadMapper, processor);
    }

    @Test
    @DisplayName("이벤트를 올바른 페이로드로 변환하여 프로세서로 위임한다")
    void handle_delegatesToProcessor() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();

        String payloadJson = """
            {
                "paymentId": "%s",
                "orderId": "%s",
                "userId": "%s",
                "orderProductId": "%s",
                "amount": 30000,
                "paymentStatus": "PARTIAL_REFUNDED",
                "refundedAt": "2026-07-17T11:00:00+09:00"
            }
        """.formatted(paymentId, aggregateId, userId, orderProductId);

        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        EventMessage<JsonNode> message = new EventMessage<>(
            eventId,
            "PAYMENT_REFUNDED",
            occurredAt,
            "PAYMENT",
            aggregateId,
            payloadNode
        );

        handler.handle(message);

        ArgumentCaptor<PaymentRefundedPayload> captor = ArgumentCaptor.forClass(PaymentRefundedPayload.class);
        then(processor).should().process(eq(eventId), eq("PAYMENT_REFUNDED"), eq(occurredAt), captor.capture());

        PaymentRefundedPayload payload = captor.getValue();
        assertThat(payload.paymentId()).isEqualTo(paymentId);
        assertThat(payload.orderId()).isEqualTo(aggregateId);
        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.orderProductId()).isEqualTo(orderProductId);
        assertThat(payload.amount()).isEqualTo(30000);
        assertThat(payload.paymentStatus()).isEqualTo("PARTIAL_REFUNDED");
        assertThat(payload.refundedAt()).isEqualTo("2026-07-17T11:00:00+09:00");
    }

    @Test
    @DisplayName("UUID 필드 형식이 잘못되면 페이로드 매핑 예외가 발생한다")
    void handle_invalidUuid_throwsException() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();

        String payloadJson = """
            {
                "paymentId": "not-a-uuid",
                "orderId": "%s"
            }
        """.formatted(aggregateId);

        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        EventMessage<JsonNode> message = new EventMessage<>(
            eventId,
            "PAYMENT_REFUNDED",
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
