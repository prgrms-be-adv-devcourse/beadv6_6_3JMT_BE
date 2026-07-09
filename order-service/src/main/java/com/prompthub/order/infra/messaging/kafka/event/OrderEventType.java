package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.common.event.EventType;

public enum OrderEventType implements EventType {
	ORDER_CREATED,
	ORDER_PAID,
	ORDER_REFUND,
	ORDER_CANCELED,
	ORDER_FAILED;

	@Override
	public String code() {
		return name();
	}
}
