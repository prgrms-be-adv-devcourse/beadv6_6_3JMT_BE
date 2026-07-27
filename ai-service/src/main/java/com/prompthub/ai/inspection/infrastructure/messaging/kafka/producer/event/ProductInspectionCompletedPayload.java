package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event;

import java.util.UUID;

/**
 * PRODUCT_INSPECTION_COMPLETED 이벤트 payload. (kafka-event.md §5)
 * 필드명(productId/approved/rejectionReason)은 product-service 소비측 계약이므로 변경하지 않는다.
 */
public record ProductInspectionCompletedPayload(
	UUID productId,
	boolean approved,
	String rejectionReason
) {
	public static ProductInspectionCompletedPayload of(UUID productId, boolean approved, String rejectionReason) {
		return new ProductInspectionCompletedPayload(productId, approved, rejectionReason);
	}
}
