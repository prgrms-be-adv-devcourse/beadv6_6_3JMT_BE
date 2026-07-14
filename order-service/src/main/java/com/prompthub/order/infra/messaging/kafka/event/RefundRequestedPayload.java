package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record RefundRequestedPayload(
    UUID refundRequestId,
    UUID paymentId,
    UUID orderId,
    UUID buyerId,
    int totalRefundAmount,
    List<RefundRequestedProductPayload> products,
    LocalDateTime requestedAt
) {
    public RefundRequestedPayload {
        products = List.copyOf(products);
    }

    public static RefundRequestedPayload from(OrderRefund refund) {
        return new RefundRequestedPayload(
            refund.getId(),
            refund.getPaymentId(),
            refund.getOrderId(),
            refund.getBuyerId(),
            refund.getTotalRefundAmount(),
            refund.getProducts().stream()
                .map(RefundRequestedProductPayload::from)
                .sorted(Comparator.comparing(RefundRequestedProductPayload::orderProductId))
                .toList(),
            refund.getRequestedAt()
        );
    }
}
