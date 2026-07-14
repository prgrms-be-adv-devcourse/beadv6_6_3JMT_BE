package com.prompthub.order.application.dto;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;

import java.time.LocalDateTime;
import java.util.UUID;

public record RefundCompletionCommand(
    UUID refundRequestId,
    UUID paymentId,
    UUID orderId,
    int totalRefundAmount,
    LocalDateTime refundedAt
) implements PaymentRefundResult {
    public RefundCompletionCommand {
        if (refundRequestId == null || paymentId == null || orderId == null
            || totalRefundAmount <= 0 || refundedAt == null) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
