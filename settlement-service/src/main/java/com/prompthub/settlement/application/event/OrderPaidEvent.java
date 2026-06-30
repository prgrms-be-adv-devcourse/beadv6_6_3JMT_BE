package com.prompthub.settlement.application.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderPaidEvent(
        UUID orderId,
        UUID buyerId,
        int totalOrderAmount,
        int totalProductCount,
        LocalDateTime paidAt,
        List<OrderPaidProduct> products
) {
}
