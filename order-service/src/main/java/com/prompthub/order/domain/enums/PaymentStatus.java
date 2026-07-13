package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제 상태: PENDING(결제 대기), PAID(결제 완료), FAILED(결제 실패), CANCELED(취소), REFUNDED(환불)")
public enum PaymentStatus {
	PENDING,
	PAID,
	FAILED,
	CANCELED,
	REFUNDED;

	public static PaymentStatus from(OrderStatus orderStatus) {
		return switch (orderStatus) {
			case PENDING -> PENDING;
			case PAID -> PAID;
			case PARTIALLY_REFUNDED -> PAID;
			case FAILED -> FAILED;
			case CANCELED -> CANCELED;
			case REFUNDED -> REFUNDED;
		};
	}
}
