package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import com.prompthub.common.event.EventType;
import java.util.Arrays;
import java.util.Optional;

/**
 * ai-service가 소비하는 product-events 중 실제로 처리하는 타입.
 * product-events에는 PRODUCT_STOPPED/DELETED/PRICE_CHANGED/CHANGED도 함께 흐르므로
 * 여기 없는 타입은 지원하지 않음으로 로그+Ack 한다.
 */
public enum ProductReviewEventType implements EventType {

	PRODUCT_REVIEW_REQUESTED;

	@Override
	public String code() {
		return name();
	}

	public static Optional<ProductReviewEventType> from(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst();
	}
}
