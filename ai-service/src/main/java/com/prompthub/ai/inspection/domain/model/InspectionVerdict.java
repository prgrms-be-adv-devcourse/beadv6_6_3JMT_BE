package com.prompthub.ai.inspection.domain.model;

/**
 * AI 검수 판정 결과. approved=false일 때만 rejectionReason이 채워진다.
 * has* 7개 필드는 상품 본문(content)에 대한 프롬프트 작성 요소 체크리스트로,
 * 참고 정보일 뿐 approved/rejectionReason 판정에는 영향을 주지 않는다.
 */
public record InspectionVerdict(
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
}
