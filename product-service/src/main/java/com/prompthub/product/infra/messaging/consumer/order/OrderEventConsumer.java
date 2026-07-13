package com.prompthub.product.infra.messaging.consumer.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.application.service.OrderEventHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-events 소비 어댑터. (kafka-event.md §7)
 * 공통 EventMessage<JsonNode> 로 수신 → eventType 확인 → 지원 타입이면 usecase(핸들러) 호출. 얇게 유지한다.
 * 미지원 eventType 은 로그+Ack(DLT 아님). 역직렬화/필수필드 누락 등은 예외로 던져 컨테이너 에러핸들러가 DLT 로 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

	private final ObjectMapper objectMapper;
	private final OrderEventHandler orderEventHandler;

	@KafkaListener(
		topics = "order-events",
		groupId = "product-service",
		containerFactory = "orderEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId/eventType 이 없는 order 이벤트");
		}

		Optional<OrderEventType> eventType = OrderEventType.from(event.eventType());
		if (eventType.isEmpty()) {
			log.info("처리하지 않는 order 이벤트 타입. eventId={}, eventType={}", event.eventId(), event.eventType());
			acknowledgment.acknowledge();
			return;
		}

		List<UUID> productIds = extractProductIds(event.payload());
		switch (eventType.get()) {
			case ORDER_PAID -> orderEventHandler.handlePaid(event.eventId(), event.occurredAt(), productIds);
			case ORDER_REFUND -> orderEventHandler.handleRefund(event.eventId(), event.occurredAt(), productIds);
		}
		acknowledgment.acknowledge();
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {
			});
		} catch (Exception e) {
			throw new IllegalArgumentException("order 이벤트 역직렬화 실패", e);
		}
	}

	private List<UUID> extractProductIds(JsonNode payload) {
		List<UUID> productIds = new ArrayList<>();
		if (payload == null) {
			return productIds;
		}
		for (JsonNode product : payload.path("products")) {
			String productId = product.path("productId").stringValue(null);
			if (productId != null) {
				productIds.add(UUID.fromString(productId));
			}
		}
		return productIds;
	}
}
