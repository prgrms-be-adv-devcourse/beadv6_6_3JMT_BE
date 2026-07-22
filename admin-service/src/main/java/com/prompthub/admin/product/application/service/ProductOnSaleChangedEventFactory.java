package com.prompthub.admin.product.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * admin-service는 common-module(EventMessage)에 의존하지 않는다(Kafka를 모름).
 * product-events가 기대하는 EventMessage 봉투와 동일한 구조를 이 안에서만 직접
 * 조립한다. (kafka-event.md §2 EventMessage 계약, admin-onsale-event-design.md §3)
 */
@Component
@RequiredArgsConstructor
public class ProductOnSaleChangedEventFactory {

	private static final String EVENT_TYPE = "PRODUCT_ON_SALE_CHANGED";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final ObjectMapper objectMapper;

	public String createEnvelopeJson(UUID familyRootId) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("eventId", UUID.randomUUID());
		envelope.put("eventType", EVENT_TYPE);
		envelope.put("occurredAt", LocalDateTime.now());
		envelope.put("aggregateType", AGGREGATE_TYPE);
		envelope.put("aggregateId", familyRootId);
		envelope.put("payload", Map.of("familyRootId", familyRootId));
		try {
			return objectMapper.writeValueAsString(envelope);
		} catch (Exception e) {
			throw new IllegalStateException("PRODUCT_ON_SALE_CHANGED 이벤트 직렬화에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}
}
