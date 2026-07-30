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
	boolean free
) {
}
