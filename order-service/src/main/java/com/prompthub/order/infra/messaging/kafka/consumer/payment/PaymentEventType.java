package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import java.util.Arrays;

public enum PaymentEventType {

	PAYMENT_APPROVED,
	PAYMENT_REFUNDED,
	PAYMENT_FAILED,
	PAYMENT_CANCELED,
	UNKNOWN;

	PaymentEventType() {
	}

	public static PaymentEventType from(String value) {
		return Arrays.stream(values())
			.filter(it -> it.name().equals(value))
			.findFirst()
			.orElse(UNKNOWN);
	}
}
