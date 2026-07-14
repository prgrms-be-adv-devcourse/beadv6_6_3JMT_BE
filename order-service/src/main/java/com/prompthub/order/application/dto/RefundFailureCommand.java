package com.prompthub.order.application.dto;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;

import java.time.LocalDateTime;
import java.util.UUID;

public record RefundFailureCommand(
    UUID refundRequestId,
    UUID paymentId,
    UUID orderId,
    int totalRefundAmount,
    String failureCode,
    String failureReason,
    boolean retryable,
    LocalDateTime failedAt
) implements PaymentRefundResult {
    public RefundFailureCommand {
        if (refundRequestId == null || paymentId == null || orderId == null
            || totalRefundAmount <= 0 || failureCode == null || failureCode.isBlank()
            || failureReason == null || failureReason.isBlank() || failedAt == null) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
