package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import java.util.Arrays;

public enum PaymentEventType {

	PAYMENT_APPROVED,
	PAYMENT_REFUNDED,
	PAYMENT_FAILED;

	PaymentEventType() {
	}

	public static PaymentEventType from(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return Arrays.stream(values())
			.filter(it -> it.name().equals(value))
			.findFirst()
			.orElse(null);
	}
}
