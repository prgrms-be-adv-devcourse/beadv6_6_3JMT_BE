package com.prompthub.order.application.dto;

import java.time.LocalDateTime;

public record PaymentRefundStatusResult(
	Status status,
	LocalDateTime refundedAt,
	String failureCode,
	String failureReason
) {
	public enum Status {
		PROCESSING,
		COMPLETED,
		FAILED,
		NOT_FOUND
	}

	public static PaymentRefundStatusResult processing() {
		return new PaymentRefundStatusResult(Status.PROCESSING, null, null, null);
	}

	public static PaymentRefundStatusResult completed(LocalDateTime refundedAt) {
		return new PaymentRefundStatusResult(Status.COMPLETED, refundedAt, null, null);
	}

	public static PaymentRefundStatusResult failed(String code, String reason) {
		return new PaymentRefundStatusResult(Status.FAILED, null, code, reason);
	}

	public static PaymentRefundStatusResult notFound() {
		return new PaymentRefundStatusResult(Status.NOT_FOUND, null, null, null);
	}
}
