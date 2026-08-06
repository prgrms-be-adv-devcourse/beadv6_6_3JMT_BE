package com.prompthub.product.infra.messaging.consumer.ai;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * product-service가 소비하는 ai-events 의 지원 이벤트 타입. (루트 kafka-event.md 참고)
 */
public enum AiEventType implements EventType {

	PRODUCT_INSPECTION_COMPLETED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<AiEventType> from(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst();
	}
}
