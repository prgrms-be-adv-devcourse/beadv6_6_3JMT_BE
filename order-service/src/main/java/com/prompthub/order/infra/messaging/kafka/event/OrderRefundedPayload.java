package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.model.OrderRefundProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;

public record OrderRefundedPayload(
    UUID orderId,
    UUID buyerId,
    int totalRefundAmount,
    LocalDateTime refundedAt,
    List<OrderRefundedProductPayload> products
) {
    public OrderRefundedPayload {
        products = List.copyOf(products);
    }

    public static OrderRefundedPayload from(OrderRefund refund, Order order, LocalDateTime refundedAt) {
        Map<UUID, OrderProduct> orderProducts = order.getOrderProducts().stream()
            .collect(Collectors.toMap(OrderProduct::getId, Function.identity()));
        List<OrderRefundedProductPayload> products = refund.getProducts().stream()
            .sorted(Comparator.comparing(OrderRefundProduct::getOrderProductId))
            .map(refundProduct -> {
                OrderProduct orderProduct = orderProducts.get(refundProduct.getOrderProductId());
                if (orderProduct == null) {
                    throw new OrderException(ErrorCode.ORDER_REFUND_RELATION_MISMATCH);
                }
                return new OrderRefundedProductPayload(
                    refundProduct.getOrderProductId(),
                    orderProduct.getProductId(),
                    orderProduct.getSellerId(),
                    refundProduct.getRefundAmount()
                );
            })
            .toList();
        return new OrderRefundedPayload(
            order.getId(), order.getBuyerId(), refund.getTotalRefundAmount(), refundedAt, products
        );
    }
}
