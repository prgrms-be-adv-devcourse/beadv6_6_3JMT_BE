package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.OrderPaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final OrderPaymentEventService orderPaymentEventService;

    @Transactional
    public void handle(PaymentEventType eventType, String eventTypeStr, JsonNode payload) {
        switch (eventType) {
            case PAYMENT_APPROVED -> orderPaymentEventService.handlePaymentApproved(toPaymentApprovedEvent(eventTypeStr, payload));
            case PAYMENT_REFUNDED -> orderPaymentEventService.handlePaymentRefunded(toPaymentRefundedEvent(eventTypeStr, payload));
            default -> log.warn("지원하지 않는 결제 이벤트 타입입니다. eventType={}", eventTypeStr);
        }
    }

    private PaymentApprovedEvent toPaymentApprovedEvent(String eventType, JsonNode payload) {
        return new PaymentApprovedEvent(
            eventType,
            UUID.fromString(payload.path("paymentId").stringValue()),
            UUID.fromString(payload.path("orderId").stringValue()),
            UUID.fromString(payload.path("userId").stringValue()),
            payload.path("amount").intValue(),
            OffsetDateTime.parse(payload.path("approvedAt").stringValue())
        );
    }

    private PaymentRefundedEvent toPaymentRefundedEvent(String eventType, JsonNode payload) {
        return new PaymentRefundedEvent(
            eventType,
            UUID.fromString(payload.path("paymentId").stringValue()),
            UUID.fromString(payload.path("orderId").stringValue()),
            UUID.fromString(payload.path("userId").stringValue()),
            payload.path("amount").intValue(),
            OffsetDateTime.parse(payload.path("refundedAt").stringValue())
        );
    }
}
