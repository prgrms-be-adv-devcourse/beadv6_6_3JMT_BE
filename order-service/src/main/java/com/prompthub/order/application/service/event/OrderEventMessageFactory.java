package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.event.order.OrderEventType;
import com.prompthub.order.application.dto.event.order.OrderExpiredPayload;
import com.prompthub.order.application.dto.event.order.OrderPaidPayload;
import com.prompthub.order.application.dto.event.order.OrderPaymentFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundRequestedPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class OrderEventMessageFactory {

    public record OrderCreatedPayload(UUID orderId, UUID buyerId, String orderNumber, LocalDateTime createdAt) {
    }

    public EventMessage<OrderCreatedPayload> createOrderCreatedMessage(UUID orderId, OrderCreatedPayload payload) {
        return new EventMessage<>(
            UUID.randomUUID(),
            OrderEventType.ORDER_CREATED.code(),
            payload.createdAt(),
            "ORDER",
            orderId,
            payload
        );
    }

    public EventMessage<OrderPaidPayload> createOrderPaidMessage(
            UUID orderId,
            OrderPaidPayload payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                OrderEventType.ORDER_PAID.code(),
                LocalDateTime.now(),
                "ORDER",
                orderId,
                payload
        );
    }

    public EventMessage<OrderRefundPayload> createOrderRefundMessage(
            UUID orderId,
            OrderRefundPayload payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                OrderEventType.ORDER_REFUND.code(),
                LocalDateTime.now(),
                "ORDER",
                orderId,
                payload
        );
    }

    public EventMessage<OrderRefundRequestedPayload> createOrderRefundRequestedMessage(
            UUID orderId,
            OrderRefundRequestedPayload payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                OrderEventType.ORDER_REFUND_REQUESTED.code(),
                payload.requestedAt(),
                "ORDER",
                orderId,
                payload
        );
    }

    public EventMessage<OrderPaymentFailedPayload> createOrderPaymentFailedMessage(
        UUID orderId,
        OrderPaymentFailedPayload payload
    ) {
        return new EventMessage<>(
            UUID.randomUUID(), OrderEventType.ORDER_PAYMENT_FAILED.code(), payload.failedAt(), "ORDER", orderId, payload
        );
    }

    public EventMessage<OrderExpiredPayload> createOrderExpiredMessage(UUID orderId, OrderExpiredPayload payload) {
        return new EventMessage<>(
            UUID.randomUUID(), OrderEventType.ORDER_EXPIRED.code(), payload.expiredAt(), "ORDER", orderId, payload
        );
    }

    public EventMessage<OrderRefundFailedPayload> createOrderRefundFailedMessage(
        UUID orderId,
        OrderRefundFailedPayload payload
    ) {
        return new EventMessage<>(
            UUID.randomUUID(), OrderEventType.ORDER_REFUND_FAILED.code(), payload.failedAt(), "ORDER", orderId, payload
        );
    }
}
