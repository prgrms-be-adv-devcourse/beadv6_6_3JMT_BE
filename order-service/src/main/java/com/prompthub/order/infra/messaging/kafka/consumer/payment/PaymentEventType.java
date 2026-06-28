package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import java.util.Arrays;

public enum PaymentEventType {

	PAYMENT_APPROVED("payment.approved"),
	PAYMENT_REFUNDED("payment.refunded"),
	UNKNOWN("");

	private final String value;

	PaymentEventType(String value) {
		this.value = value;
	}

	public static PaymentEventType from(String value) {
		return Arrays.stream(values())
			.filter(it -> it.value.equals(value))
			.findFirst()
			.orElse(UNKNOWN);
	}
}
