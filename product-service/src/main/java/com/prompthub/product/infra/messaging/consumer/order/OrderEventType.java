package com.prompthub.product.infra.messaging.consumer.order;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * product-service가 소비하는 order-events 의 지원 이벤트 타입. (kafka-event.md §4)
 * 미지원 타입은 {@link #from(String)} 이 empty 를 돌려주고 컨슈머가 로그+Ack 로 넘긴다.
 */
public enum OrderEventType implements EventType {

	ORDER_PAID,
	ORDER_REFUND;

	@Override
	public String code() {
		return name();
	}

	public static Optional<OrderEventType> from(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst();
	}
}
