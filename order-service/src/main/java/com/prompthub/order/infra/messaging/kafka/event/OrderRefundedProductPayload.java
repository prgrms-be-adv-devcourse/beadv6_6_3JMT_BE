package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record OrderRefundedProductPayload(
    UUID orderProductId,
    UUID productId,
    UUID sellerId,
    int refundAmount
) {
}
