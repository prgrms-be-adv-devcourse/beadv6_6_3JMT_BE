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
	List<String> imageUrls
) {
	public static ProductReviewRequestedPayload of(
		UUID productId, String productType, String name, String description,
		String content, List<String> tags, String thumbnailUrl, List<String> imageUrls
	) {
		return new ProductReviewRequestedPayload(
			productId, productType, name, description, content, tags, thumbnailUrl, imageUrls);
	}
}
