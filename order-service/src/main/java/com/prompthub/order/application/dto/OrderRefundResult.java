package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderRefundResult(
    UUID refundRequestId,
    UUID orderId,
    UUID paymentId,
    List<UUID> orderProductIds,
    int totalRefundAmount,
    OrderRefundStatus status,
    LocalDateTime requestedAt
) {
    public OrderRefundResult {
        orderProductIds = List.copyOf(orderProductIds);
    }

    public static OrderRefundResult from(OrderRefund refund) {
        return new OrderRefundResult(
            refund.getId(),
            refund.getOrderId(),
            refund.getPaymentId(),
            refund.productIds().stream().sorted().toList(),
            refund.getTotalRefundAmount(),
            refund.getStatus(),
            refund.getRequestedAt()
        );
    }
}
