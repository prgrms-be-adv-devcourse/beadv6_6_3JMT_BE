package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;

public record OrderRefundPayload(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        LocalDateTime refundedAt,
        List<OrderPaidProductPayload> products
) {
    public static OrderRefundPayload from(
        Order order,
        OrderProduct refundedProduct,
        LocalDateTime refundedAt
    ) {
        return new OrderRefundPayload(
                order.getId(),
                order.getBuyerId(),
                order.getTotalOrderAmount(),
                refundedAt,
				List.of(OrderPaidProductPayload.from(refundedProduct))
        );
    }
}
