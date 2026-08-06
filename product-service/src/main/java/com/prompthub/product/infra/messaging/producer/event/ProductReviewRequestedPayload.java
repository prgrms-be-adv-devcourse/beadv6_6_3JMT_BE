package com.prompthub.product.infra.messaging.producer.event;

import java.util.List;
import java.util.UUID;

/**
 * PRODUCT_REVIEW_REQUESTED 이벤트 payload. (루트 kafka-event.md 참고)
 * ai-service가 재조회 없이 바로 검수할 수 있도록 콘텐츠 스냅샷을 담는다.
 * thumbnailUrl/imageUrls는 raw S3 key가 아니라 발행 시점에 presign된 URL이다.
 */
public record ProductReviewRequestedPayload(
	UUID productId,
	String productType,
	String name,
	String description,
	String content,
	List<String> tags,
	String thumbnailUrl,
	List<String> imageUrls,
	/**
	 * 같은 본문(content_hash)을 가진 다른 판매자의 먼저 등록된 상품 id. 없으면 {@code null}
	 * (ADR-0011). 완전일치라 이진 판정이라 ai-service는 이 값이 있으면 그대로 자동 반려한다.
	 */
	UUID duplicateOfProductId,
	boolean free
) {
	public static ProductReviewRequestedPayload of(
		UUID productId, String productType, String name, String description,
		String content, List<String> tags, String thumbnailUrl, List<String> imageUrls,
		UUID duplicateOfProductId, boolean free
	) {
		return new ProductReviewRequestedPayload(
			productId, productType, name, description, content, tags, thumbnailUrl, imageUrls,
			duplicateOfProductId, free);
	}
}
