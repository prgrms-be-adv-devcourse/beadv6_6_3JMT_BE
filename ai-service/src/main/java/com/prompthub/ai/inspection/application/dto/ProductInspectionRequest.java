package com.prompthub.ai.inspection.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * PRODUCT_REVIEW_REQUESTED 이벤트 payload를 애플리케이션 계층 입력으로 옮긴 값.
 * thumbnailUrl/imageUrls는 product-service가 presign한 URL이다.
 */
public record ProductInspectionRequest(
	UUID productId,
	String productType,
	String name,
	String description,
	String content,
	List<String> tags,
	String thumbnailUrl,
	List<String> imageUrls,
	/**
	 * 같은 본문을 가진 다른 판매자의 먼저 등록된 상품 id. product-service가 완전일치로
	 * 판정해 실어 보낸 값이라 이진(non-null이면 복제) — ai-service는 별도 임계값 없이
	 * 그대로 자동 반려한다(ADR-0011).
	 */
	UUID duplicateOfProductId,
	boolean free
) {
}
