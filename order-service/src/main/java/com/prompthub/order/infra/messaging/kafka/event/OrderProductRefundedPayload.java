package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderProductRefundedPayload(
	UUID refundRequestId,
	UUID orderId,
	UUID buyerId,
	int totalRefundAmount,
	LocalDateTime refundedAt,
	List<OrderRefundedProductPayload> products
) {
	public static OrderProductRefundedPayload from(OrderRefund refund, LocalDateTime refundedAt) {
		return new OrderProductRefundedPayload(
			refund.getId(),
			refund.getOrderId(),
			refund.getBuyerId(),
			refund.getTotalRefundAmount(),
			refundedAt,
			refund.getRefundProducts().stream()
				.map(item -> new OrderRefundedProductPayload(
					item.getOrderProduct().getId(),
					item.getOrderProduct().getProductId(),
					item.getOrderProduct().getSellerId(),
					item.getRefundAmount()
				))
				.toList()
		);
	}

	public record OrderRefundedProductPayload(
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		int refundAmount
	) {
	}
}
