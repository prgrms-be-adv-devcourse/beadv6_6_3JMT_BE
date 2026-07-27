package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.common.event.EventType;

import java.util.Arrays;
import java.util.Optional;

public enum OrderEventType implements EventType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_PAYMENT_FAILED,
    ORDER_EXPIRED,
    ORDER_REFUND_REQUESTED,
    ORDER_REFUND,
    ORDER_REFUND_FAILED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<OrderEventType> from(String value) {
		if (value == null) {
			return Optional.empty();
		}
		return Arrays.stream(values())
				.filter(type -> type.name().equals(value))
				.findFirst();
	}
}
