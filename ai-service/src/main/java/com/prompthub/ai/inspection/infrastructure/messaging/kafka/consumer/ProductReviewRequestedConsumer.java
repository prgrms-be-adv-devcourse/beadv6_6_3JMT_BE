package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import com.prompthub.common.event.EventMessage;
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
 * product-events 소비 어댑터. (kafka-event.md §7)
 * PRODUCT_REVIEW_REQUESTED 외 타입(PRODUCT_STOPPED 등)은 로그+Ack(DLT 아님).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductReviewRequestedConsumer {

	private final ObjectMapper objectMapper;
	private final ProductInspectionUseCase productInspectionUseCase;

	@KafkaListener(
		topics = "product-events",
		groupId = "ai-service",
		containerFactory = "productEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId/eventType 이 없는 product 이벤트");
		}

		Optional<ProductReviewEventType> eventType = ProductReviewEventType.from(event.eventType());
		if (eventType.isEmpty()) {
			log.info("처리하지 않는 product 이벤트 타입. eventId={}, eventType={}", event.eventId(), event.eventType());
			acknowledgment.acknowledge();
			return;
		}

		productInspectionUseCase.inspect(toRequest(event.payload()));
		acknowledgment.acknowledge();
	}

	private ProductInspectionRequest toRequest(JsonNode payload) {
		return new ProductInspectionRequest(
			UUID.fromString(payload.path("productId").stringValue(null)),
			payload.path("productType").stringValue(null),
			payload.path("name").stringValue(null),
			payload.path("description").stringValue(null),
			payload.path("content").stringValue(null),
			toStringList(payload.path("tags")),
			payload.path("thumbnailUrl").stringValue(null),
			toStringList(payload.path("imageUrls")),
			toUuidOrNull(payload.path("duplicateOfProductId")));
	}

	private UUID toUuidOrNull(JsonNode node) {
		String value = node.stringValue(null);
		return value != null ? UUID.fromString(value) : null;
	}

	private List<String> toStringList(JsonNode arrayNode) {
		List<String> values = new ArrayList<>();
		for (JsonNode element : arrayNode) {
			String value = element.stringValue(null);
			if (value != null) {
				values.add(value);
			}
		}
		return values;
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {
			});
		} catch (Exception e) {
			throw new IllegalArgumentException("product 이벤트 역직렬화 실패", e);
		}
	}
}
