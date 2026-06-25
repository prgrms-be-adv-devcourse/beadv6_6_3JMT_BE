package com.prompthub.settlement.application.event;

import java.util.UUID;

public record OrderRefundedProduct(
        UUID orderProductId,
        UUID productId,
        UUID sellerId,
        int refundAmount
) {
}
