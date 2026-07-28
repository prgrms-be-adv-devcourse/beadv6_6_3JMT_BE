package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event;

import java.util.UUID;

/**
 * PRODUCT_INSPECTION_COMPLETED 이벤트 payload. (kafka-event.md §5)
 * productId/approved/rejectionReason 필드명은 product-service 소비측 계약이므로 변경하지 않는다.
 * has* 7개 필드는 이번에 추가된 참고용 체크리스트 값으로, additive이며 기존 계약을 깨지 않는다.
 */
public record ProductInspectionCompletedPayload(
	UUID productId,
	boolean approved,
	String rejectionReason,
	boolean hasContext,
	boolean hasObjective,
	boolean hasNuance,
	boolean hasTone,
	boolean hasExamples,
	boolean hasExecution,
	boolean hasRoleAssignment
) {
	public static ProductInspectionCompletedPayload of(
		UUID productId, boolean approved, String rejectionReason,
		boolean hasContext, boolean hasObjective, boolean hasNuance,
		boolean hasTone, boolean hasExamples, boolean hasExecution, boolean hasRoleAssignment
	) {
		return new ProductInspectionCompletedPayload(
			productId, approved, rejectionReason,
			hasContext, hasObjective, hasNuance, hasTone, hasExamples, hasExecution, hasRoleAssignment);
	}
}
