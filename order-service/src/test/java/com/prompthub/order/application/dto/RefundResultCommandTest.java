package com.prompthub.order.application.dto;

import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundResultCommandTest {

    @Test
    void completion_requiresIdsPositiveAmountAndTimestamp() {
        assertThatThrownBy(() -> new RefundCompletionCommand(
            null, UUID.randomUUID(), UUID.randomUUID(), 1, LocalDateTime.now()
        )).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> new RefundCompletionCommand(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, LocalDateTime.now()
        )).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> new RefundCompletionCommand(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null
        )).isInstanceOf(OrderException.class);
    }

    @Test
    void failure_requiresIdsPositiveAmountCodeReasonAndTimestamp() {
        UUID requestId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LocalDateTime failedAt = LocalDateTime.now();
        assertThatThrownBy(() -> new RefundFailureCommand(
            requestId, paymentId, orderId, 1, " ", "reason", false, failedAt
        )).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> new RefundFailureCommand(
            requestId, paymentId, orderId, 1, "CODE", "", false, failedAt
        )).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> new RefundFailureCommand(
            requestId, paymentId, orderId, -1, "CODE", "reason", false, failedAt
        )).isInstanceOf(OrderException.class);
    }
}
