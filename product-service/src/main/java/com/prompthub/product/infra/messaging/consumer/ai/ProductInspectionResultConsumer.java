package com.prompthub.product.infra.messaging.consumer.ai;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.application.service.inspection.ProductInspectionResultHandler;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
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
 * ai-events 소비 어댑터. (루트 kafka-event.md 참고)
 * 미지원 eventType 은 로그+Ack(DLT 아님). 중복/이미 처리된 상품은 handler가 내부에서
 * 조용히 스킵하므로(예외를 던지지 않음) 이 consumer는 정상 흐름만 처리하면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductInspectionResultConsumer {

	private final ObjectMapper objectMapper;
	private final ProductInspectionResultHandler productInspectionResultHandler;

	@KafkaListener(
		topics = "ai-events",
		groupId = "product-service",
		containerFactory = "aiEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		EventMessage<JsonNode> event = parse(message);

		if (event.eventId() == null || event.eventType() == null) {
			throw new IllegalArgumentException("eventId/eventType 이 없는 ai 이벤트");
		}

		Optional<AiEventType> eventType = AiEventType.from(event.eventType());
		if (eventType.isEmpty()) {
			log.info("처리하지 않는 ai 이벤트 타입. eventId={}, eventType={}", event.eventId(), event.eventType());
			acknowledgment.acknowledge();
			return;
		}

		JsonNode payload = event.payload();
		UUID productId = UUID.fromString(payload.path("productId").stringValue(null));
		boolean approved = payload.path("approved").asBoolean(false);
		String rejectionReason = payload.path("rejectionReason").stringValue(null);
		InspectionChecklist checklist = new InspectionChecklist(
			payload.path("hasContext").asBoolean(false),
			payload.path("hasObjective").asBoolean(false),
			payload.path("hasNuance").asBoolean(false),
			payload.path("hasTone").asBoolean(false),
			payload.path("hasExamples").asBoolean(false),
			payload.path("hasExecution").asBoolean(false),
			payload.path("hasRoleAssignment").asBoolean(false)
		);

		productInspectionResultHandler.apply(productId, approved, rejectionReason, checklist);
		acknowledgment.acknowledge();
	}

	private EventMessage<JsonNode> parse(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {
			});
		} catch (Exception e) {
			throw new IllegalArgumentException("ai 이벤트 역직렬화 실패", e);
		}
	}
}
