package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.Order;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderCreatedPayload(
        UUID orderId,
        UUID buyerId,
        String orderNumber,
        int totalAmount,
        String orderStatus,
        LocalDateTime createdAt
) {
    public static OrderCreatedPayload from(Order order) {
        return new OrderCreatedPayload(
                order.getId(),
                order.getBuyerId(),
                order.getOrderNumber(),
                order.getTotalOrderAmount(),
                order.getOrderStatus().name(),
                order.getCreatedAt()
        );
    }
}
