package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;

public record OrderPaidPayload(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        int totalProductCount,
        LocalDateTime paidAt,
        List<OrderPaidProductPayload> products
) {
    public static OrderPaidPayload from(Order order) {
        return new OrderPaidPayload(
                order.getId(),
                order.getBuyerId(),
                order.getTotalOrderAmount(),
                order.getOrderProducts().size(),
                order.getPaidAt(),
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
