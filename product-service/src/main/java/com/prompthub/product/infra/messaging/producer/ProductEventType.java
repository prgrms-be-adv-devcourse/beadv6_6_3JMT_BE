package com.prompthub.product.infra.messaging.producer;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * product-service가 발행하는 product-events 의 이벤트 타입. (루트 kafka-event.md 참고)
 * 공통 {@link EventType} 을 구현하고 code() 는 name()(UPPER_SNAKE) 을 반환한다.
 */
public enum ProductEventType implements EventType {

	PRODUCT_REVIEW_REQUESTED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductEventType> from(String code) {
		return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
	}
}
