package com.prompthub.search.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.infra.messaging.producer.ProductEventType;
import com.prompthub.search.application.ProductSearchEventHandler;
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
	private final ProductSearchEventHandler productSearchEventHandler;

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
			type -> handle(type, event),
			() -> log.info("색인 컨슈머가 지원하지 않는 eventType입니다. eventType={}", event.eventType())
		);

		acknowledgment.acknowledge();
	}

	private void handle(ProductEventType type, EventMessage<JsonNode> event) {
		switch (type) {
			case PRODUCT_ON_SALE_CHANGED -> {
				UUID familyRootId = UUID.fromString(event.payload().get("familyRootId").asText());
				productSearchEventHandler.handleOnSaleChanged(event.eventId(), event.occurredAt(), familyRootId);
			}
			case PRODUCT_STOPPED, PRODUCT_DELETED -> {
				UUID productId = UUID.fromString(event.payload().get("productId").asText());
				productSearchEventHandler.handleStoppedOrDeleted(event.eventId(), event.occurredAt(), productId, event.eventType());
			}
			case PRODUCT_PRICE_CHANGED -> {
				UUID productId = UUID.fromString(event.payload().get("productId").asText());
				int changedPrice = event.payload().get("changedPrice").asInt();
				productSearchEventHandler.handlePriceChanged(event.eventId(), event.occurredAt(), productId, changedPrice);
			}
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
