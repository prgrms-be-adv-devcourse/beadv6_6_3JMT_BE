package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import com.prompthub.common.event.EventType;

/**
 * ai-service가 발행하는 ai-events 의 이벤트 타입. (kafka-event.md §4)
 */
public enum InspectionEventType implements EventType {

	PRODUCT_INSPECTION_COMPLETED;

	@Override
	public String code() {
		return name();
	}
}
