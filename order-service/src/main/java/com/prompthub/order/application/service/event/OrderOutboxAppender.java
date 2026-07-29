package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.event.order.OrderExpiredPayload;
import com.prompthub.order.application.dto.event.order.OrderPaidPayload;
import com.prompthub.order.application.dto.event.order.OrderPaymentFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundRequestedPayload;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderOutboxAppender {

    private final OrderEventMessageFactory orderEventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;

    public void appendCreated(Order order) {
        EventMessage<OrderEventMessageFactory.OrderCreatedPayload> message = orderEventMessageFactory.createOrderCreatedMessage(
            order.getId(),
            new OrderEventMessageFactory.OrderCreatedPayload(order.getId(), order.getBuyerId(), order.getOrderNumber(), order.getCreatedAt())
        );
        outboxEventAppender.append(message);
    }

    public void appendPaid(Order order) {
        EventMessage<OrderPaidPayload> message = orderEventMessageFactory.createOrderPaidMessage(
            order.getId(), OrderPaidPayload.from(order)
        );
        outboxEventAppender.append(message);
    }

    public void appendRefundRequested(UUID orderId, OrderRefundRequestedPayload payload) {
        EventMessage<OrderRefundRequestedPayload> message =
            orderEventMessageFactory.createOrderRefundRequestedMessage(orderId, payload);
        outboxEventAppender.append(message);
    }

    public void appendPaymentFailed(Order order, String failureCode, String failureReason, LocalDateTime failedAt) {
        EventMessage<OrderPaymentFailedPayload> message = orderEventMessageFactory.createOrderPaymentFailedMessage(
            order.getId(), new OrderPaymentFailedPayload(
                order.getId(), order.getBuyerId(), order.getOrderNumber(), failureCode, failureReason, failedAt
            )
        );
        outboxEventAppender.append(message);
    }

    public void appendExpired(Order order, LocalDateTime expiredAt) {
        EventMessage<OrderExpiredPayload> message = orderEventMessageFactory.createOrderExpiredMessage(
            order.getId(), new OrderExpiredPayload(order.getId(), order.getBuyerId(), order.getOrderNumber(), expiredAt)
        );
        outboxEventAppender.append(message);
    }

    public void appendRefundFailed(Order order, int refundAmount, LocalDateTime failedAt) {
        EventMessage<OrderRefundFailedPayload> message = orderEventMessageFactory.createOrderRefundFailedMessage(
            order.getId(), new OrderRefundFailedPayload(
                order.getId(), order.getBuyerId(), order.getOrderNumber(), refundAmount, failedAt
            )
        );
        outboxEventAppender.append(message);
    }

    public void appendRefunded(Order order, List<OrderProduct> refundedProducts, LocalDateTime refundedAt) {
        EventMessage<OrderRefundPayload> message = orderEventMessageFactory.createOrderRefundMessage(
            order.getId(), OrderRefundPayload.from(order, refundedProducts, refundedAt)
        );
        outboxEventAppender.append(message);
    }
}
