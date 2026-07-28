package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event.ProductInspectionCompletedPayload;
import com.prompthub.common.event.EventMessage;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * ai-events 발행 어댑터. (kafka-event.md §6)
 * ai-service는 이 발행에 앞선 DB 트랜잭션이 없으므로(무상태 검수) product-service의
 * ProductEventProducer와 달리 AFTER_COMMIT 지연 없이 즉시 발행한다.
 */
@Component
@RequiredArgsConstructor
public class InspectionEventProducer {

	private static final String TOPIC = "ai-events";
	private static final String AGGREGATE_TYPE = "PRODUCT";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void publish(
		UUID productId, boolean approved, String rejectionReason,
		boolean hasContext, boolean hasObjective, boolean hasNuance,
		boolean hasTone, boolean hasExamples, boolean hasExecution, boolean hasRoleAssignment
	) {
		EventMessage<Object> message = new EventMessage<>(
			UUID.randomUUID(),
			InspectionEventType.PRODUCT_INSPECTION_COMPLETED.code(),
			LocalDateTime.now(),
			AGGREGATE_TYPE,
			productId,
			ProductInspectionCompletedPayload.of(
				productId, approved, rejectionReason,
				hasContext, hasObjective, hasNuance, hasTone, hasExamples, hasExecution, hasRoleAssignment)
		);
		kafkaTemplate.send(TOPIC, productId.toString(), message);
	}
}
