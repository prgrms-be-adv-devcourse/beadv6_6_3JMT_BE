package com.prompthub.search.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.infra.messaging.producer.ProductEventType;
import com.prompthub.product.infra.messaging.producer.event.ProductDeletedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedPayload;
import com.prompthub.search.application.indexing.ProductSearchEventProcessor;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchEventConsumer {

	private final ObjectMapper objectMapper;
	private final ProductSearchEventProcessor productSearchEventProcessor;

	@KafkaListener(
		topics = "product-events",
		groupId = "product-service-search",
		containerFactory = "productEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId 또는 eventType이 없습니다. message=" + message);
		}

		ProductEventType.from(event.eventType()).ifPresentOrElse(
			type -> routeProductEvent(type, event),
			() -> log.info("색인 컨슈머가 지원하지 않는 eventType입니다. eventType={}", event.eventType())
		);

		acknowledgment.acknowledge();
	}

	private void routeProductEvent(ProductEventType type, EventMessage<JsonNode> event) {
		switch (type) {
			case PRODUCT_CHANGED -> {
				UUID familyRootId = UUID.fromString(event.payload().get("familyRootId").asText());
				productSearchEventProcessor.processProductChanged(event.eventId(), event.occurredAt(), familyRootId);
			}
			case PRODUCT_STOPPED -> routeRemovalEvent(
				type, event, mapPayload(event.payload(), ProductStoppedPayload.class).productId());
			case PRODUCT_DELETED -> routeRemovalEvent(
				type, event, mapPayload(event.payload(), ProductDeletedPayload.class).productId());
			default -> log.info("색인 컨슈머가 처리하지 않는 eventType입니다. eventType={}", type);
		}
	}

	private void routeRemovalEvent(ProductEventType type, EventMessage<JsonNode> event, UUID productId) {
		Objects.requireNonNull(productId, type.name() + " payload에 productId가 없습니다.");
		productSearchEventProcessor.processRemovalCandidate(event.eventId(), event.occurredAt(), type.name(), productId);
	}

	private <T> T mapPayload(JsonNode payload, Class<T> type) {
		try {
			return objectMapper.treeToValue(payload, type);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("product-events payload 매핑에 실패했습니다. type=" + type.getSimpleName(), e);
		}
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() { });
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("product-events 메시지 역직렬화에 실패했습니다.", e);
		}
	}
}
