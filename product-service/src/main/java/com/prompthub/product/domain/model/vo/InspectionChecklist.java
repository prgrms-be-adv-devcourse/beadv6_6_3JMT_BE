package com.prompthub.product.domain.model.vo;

/**
 * AI 검수 체크리스트 판정 결과 (ai-events {@code PRODUCT_INSPECTION_COMPLETED} payload 값).
 * approve()/reject() 호출 시 함께 저장해, 승인/반려 여부만으로는 드러나지 않는 세부 판정
 * 근거를 product-service에서도 조회 가능하게 한다.
 */
public record InspectionChecklist(
	boolean hasContext,
	boolean hasObjective,
	boolean hasNuance,
	boolean hasTone,
	boolean hasExamples,
	boolean hasExecution,
	boolean hasRoleAssignment
) {
}
