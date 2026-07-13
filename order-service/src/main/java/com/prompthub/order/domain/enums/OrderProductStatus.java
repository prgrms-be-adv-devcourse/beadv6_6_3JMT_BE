package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상품 상태")
public enum OrderProductStatus {
	PENDING,
	PAID,
	FAILED,
	CANCELED,
	REFUND_REQUESTED,
	REFUNDED,
	REFUND_FAILED,
	REFUND_TIMEOUT;

	public boolean canTransitionTo(OrderProductStatus target) {
		return switch (this) {
			case PENDING -> target == PAID || target == FAILED || target == CANCELED;
			case FAILED -> target == PAID;
			case PAID -> target == CANCELED || target == REFUND_REQUESTED || target == REFUNDED;
			case REFUND_REQUESTED -> target == REFUNDED || target == REFUND_FAILED || target == REFUND_TIMEOUT;
			case REFUND_FAILED -> false;
			case REFUND_TIMEOUT -> target == REFUNDED || target == REFUND_FAILED;
			case CANCELED, REFUNDED -> false;
		};
	}
}
