package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RefundRequestedPayload(
	UUID refundId,
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	int totalRefundAmount,
	String reason,
	List<RefundRequestedProductPayload> products,
	LocalDateTime requestedAt
) {
	public static RefundRequestedPayload from(OrderRefund refund) {
		return new RefundRequestedPayload(
			refund.getId(),
			refund.getPaymentId(),
			refund.getOrderId(),
			refund.getBuyerId(),
			refund.getTotalRefundAmount(),
			refund.getReason(),
			refund.getRefundProducts().stream()
				.map(item -> new RefundRequestedProductPayload(
					item.getOrderProduct().getId(),
					item.getOrderProduct().getProductId(),
					item.getRefundAmount()
				))
				.toList(),
			refund.getRequestedAt()
		);
	}

	public record RefundRequestedProductPayload(
		UUID orderProductId,
		UUID productId,
		int refundAmount
	) {
	}
}
