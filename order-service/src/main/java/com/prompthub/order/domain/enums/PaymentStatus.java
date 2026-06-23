package com.prompthub.order.domain.enums;

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
			case FAILED -> FAILED;
			case CANCELED -> CANCELED;
			case REFUNDED -> REFUNDED;
		};
	}
}
