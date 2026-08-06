package com.prompthub.ai.inspection.domain.model;

import java.util.UUID;

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

	/**
	 * 완전일치 본문 복제 판정(ADR-0011). AI 판단 없이 즉시 반려한다 — 이진 판정이라
	 * 임계값도, 체크리스트 재확인도 필요 없다.
	 */
	public static InspectionVerdict rejectedAsDuplicate(UUID duplicateOfProductId) {
		return new InspectionVerdict(
			false, "기존 상품과 동일한 본문입니다. (원본 상품 ID: " + duplicateOfProductId + ")",
			false, false, false, false, false, false, false);
	}
}
