package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.domain.enums.OrderRefundStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderRefundResponse(
    UUID refundRequestId,
    UUID orderId,
    UUID paymentId,
    List<UUID> orderProductIds,
    int totalRefundAmount,
    OrderRefundStatus status,
    LocalDateTime requestedAt
) {
    public OrderRefundResponse {
        orderProductIds = List.copyOf(orderProductIds);
    }

    public static OrderRefundResponse from(OrderRefundResult result) {
        return new OrderRefundResponse(
            result.refundRequestId(),
            result.orderId(),
            result.paymentId(),
            result.orderProductIds(),
            result.totalRefundAmount(),
            result.status(),
            result.requestedAt()
        );
    }
}
