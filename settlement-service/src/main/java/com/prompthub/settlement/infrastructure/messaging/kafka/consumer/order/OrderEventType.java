package com.prompthub.settlement.infrastructure.messaging.kafka.consumer.order;

import java.util.Arrays;

public enum OrderEventType {

	ORDER_PAID,
	ORDER_REFUNDED,
	UNKNOWN;

	public static OrderEventType from(String type) {
		if (type == null) {
			return UNKNOWN;
		}
		return Arrays.stream(values())
			.filter(eventType -> eventType.name().equals(type))
			.findFirst()
			.orElse(UNKNOWN);
	}
}
