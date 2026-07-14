package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.application.dto.RefundCompletionCommand;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentPartialRefundedPayload(
    UUID refundRequestId,
    UUID paymentId,
    UUID orderId,
    int totalRefundAmount,
    LocalDateTime refundedAt
) {
    public RefundCompletionCommand toCommand() {
        return new RefundCompletionCommand(
            refundRequestId, paymentId, orderId, totalRefundAmount, refundedAt
        );
    }
}
