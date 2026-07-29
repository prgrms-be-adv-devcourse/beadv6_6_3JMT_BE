package com.prompthub.order.application.dto.event.order;

import com.prompthub.order.domain.model.Order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderPaidPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    int totalOrderAmount,
    int totalProductCount,
    LocalDateTime paidAt,
    List<OrderPaidProductPayload> products
) {
    public OrderPaidPayload(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        int totalProductCount,
        LocalDateTime paidAt,
        List<OrderPaidProductPayload> products
    ) {
        this(orderId, buyerId, null, totalOrderAmount, totalProductCount, paidAt, products);
    }

    public static OrderPaidPayload from(Order order) {
        return new OrderPaidPayload(
            order.getId(),
            order.getBuyerId(),
            order.getOrderNumber(),
            order.getTotalOrderAmount(),
            order.getOrderProducts().size(),
            order.getPaidAt(),
            order.getOrderProducts().stream()
                .map(OrderPaidProductPayload::from)
                .toList()
        );
    }
}
