package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.OrderPaymentEventService;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
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
    public void handle(PaymentEventType eventType, String eventTypeStr, String consumerGroup, JsonNode root) {
        switch (eventType) {
            case PAYMENT_APPROVED -> orderPaymentEventService.handlePaymentApproved(toPaymentApprovedEvent(root));
            case PAYMENT_REFUNDED -> orderPaymentEventService.handlePaymentRefunded(toPaymentRefundedEvent(root));
            case UNKNOWN -> log.warn("지원하지 않는 결제 이벤트 타입입니다. eventType={}", eventTypeStr);
        }
    }

    private PaymentApprovedEvent toPaymentApprovedEvent(JsonNode root) {
        return new PaymentApprovedEvent(
            root.path("eventType").stringValue(null),
            UUID.fromString(root.path("paymentId").stringValue()),
            UUID.fromString(root.path("orderId").stringValue()),
            UUID.fromString(root.path("userId").stringValue()),
            root.path("amount").intValue(),
            OffsetDateTime.parse(root.path("approvedAt").stringValue())
        );
    }

    private PaymentRefundedEvent toPaymentRefundedEvent(JsonNode root) {
        return new PaymentRefundedEvent(
            root.path("eventType").stringValue(null),
            UUID.fromString(root.path("paymentId").stringValue()),
            UUID.fromString(root.path("orderId").stringValue()),
            UUID.fromString(root.path("userId").stringValue()),
            root.path("amount").intValue(),
            OffsetDateTime.parse(root.path("refundedAt").stringValue())
        );
    }
}
