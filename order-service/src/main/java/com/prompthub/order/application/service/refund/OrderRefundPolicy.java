package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class OrderRefundPolicy {

    public Optional<OrderRefund> resolve(
        UUID paymentId,
        Set<UUID> requestedProductIds,
        List<OrderRefund> refundsNewestFirst
    ) {
        List<OrderRefund> overlaps = refundsNewestFirst.stream()
            .filter(refund -> refund.getPaymentId().equals(paymentId))
            .filter(refund -> refund.overlaps(requestedProductIds))
            .toList();

        if (overlaps.stream().anyMatch(refund -> refund.getStatus() == OrderRefundStatus.UNKNOWN)) {
            throw new OrderException(ErrorCode.ORDER_REFUND_RESULT_UNKNOWN);
        }

        List<OrderRefund> active = overlaps.stream()
            .filter(refund -> refund.getStatus() == OrderRefundStatus.REQUESTED
                || refund.getStatus() == OrderRefundStatus.PROCESSING)
            .toList();
        if (active.stream().anyMatch(refund -> !refund.hasExactProducts(paymentId, requestedProductIds))) {
            throw new OrderException(ErrorCode.ORDER_REFUND_IN_PROGRESS);
        }
        if (!active.isEmpty()) {
            return Optional.of(active.getFirst());
        }

        Optional<OrderRefund> completed = overlaps.stream()
            .filter(refund -> refund.getStatus() == OrderRefundStatus.COMPLETED)
            .filter(refund -> refund.hasExactProducts(paymentId, requestedProductIds))
            .findFirst();
        if (completed.isPresent()) {
            return completed;
        }

        Optional<OrderRefund> latestFailedOverlap = overlaps.stream()
            .filter(refund -> refund.getStatus() == OrderRefundStatus.FAILED)
            .findFirst();
        if (latestFailedOverlap.isPresent() && !latestFailedOverlap.get().isRetryable()) {
            throw new OrderException(ErrorCode.ORDER_REFUND_RETRY_NOT_ALLOWED);
        }
        return Optional.empty();
    }
}
