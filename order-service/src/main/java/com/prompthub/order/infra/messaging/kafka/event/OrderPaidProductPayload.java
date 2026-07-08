package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record OrderPaidProductPayload(
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        String productTitle,
        String productType,
        int productAmount
) {
}
