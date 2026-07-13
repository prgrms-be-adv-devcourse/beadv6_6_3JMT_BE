package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderRefundResponse(
	UUID refundId,
	UUID orderId,
	List<UUID> orderProductIds,
	int totalRefundAmount,
	OrderRefundStatus status,
	LocalDateTime requestedAt
) {
	public static OrderRefundResponse from(OrderRefund refund) {
		return new OrderRefundResponse(
			refund.getId(),
			refund.getOrderId(),
			refund.getRefundProducts().stream().map(item -> item.getOrderProduct().getId()).toList(),
			refund.getTotalRefundAmount(),
			refund.getStatus(),
			refund.getRequestedAt()
		);
	}
}
