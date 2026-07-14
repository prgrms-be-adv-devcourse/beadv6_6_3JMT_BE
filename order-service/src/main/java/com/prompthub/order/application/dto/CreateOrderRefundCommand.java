package com.prompthub.order.application.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record CreateOrderRefundCommand(
    UUID buyerId,
    UUID orderId,
    UUID paymentId,
    List<UUID> orderProductIds
) {
    public CreateOrderRefundCommand {
        if (orderProductIds != null) {
            orderProductIds = Collections.unmodifiableList(new ArrayList<>(orderProductIds));
        }
    }
}
