package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.application.dto.RefundFailureCommand;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentPartialRefundFailedPayload(
    UUID refundRequestId,
    UUID paymentId,
    UUID orderId,
    int totalRefundAmount,
    String failureCode,
    String failureReason,
    boolean retryable,
    LocalDateTime failedAt
) {
    public RefundFailureCommand toCommand() {
        return new RefundFailureCommand(
            refundRequestId, paymentId, orderId, totalRefundAmount,
            failureCode, failureReason, retryable, failedAt
        );
    }
}
