package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.OrderRefundProduct;

import java.util.UUID;

public record RefundRequestedProductPayload(
    UUID orderProductId,
    int refundAmount
) {
    public static RefundRequestedProductPayload from(OrderRefundProduct product) {
        return new RefundRequestedProductPayload(product.getOrderProductId(), product.getRefundAmount());
    }
}
