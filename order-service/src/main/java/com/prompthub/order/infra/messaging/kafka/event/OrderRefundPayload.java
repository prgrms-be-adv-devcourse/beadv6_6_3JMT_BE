package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.prompthub.order.domain.model.Order;

public record OrderRefundPayload(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        LocalDateTime refundedAt,
        List<OrderPaidProductPayload> products
) {
    public static OrderRefundPayload from(Order order, LocalDateTime refundedAt) {
        return new OrderRefundPayload(
                order.getId(),
                order.getBuyerId(),
                order.getTotalOrderAmount(),
                refundedAt,
                order.getOrderProducts().stream()
                        .map(op -> new OrderPaidProductPayload(
                                op.getId(),
                                op.getProductId(),
                                op.getSellerId(),
                                op.getProductTitle(),
                                op.getProductType(),
                                op.getProductAmount()
                        ))
                        .toList()
        );
    }
}
