package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

/**
 * PRODUCT_DELETED 이벤트 payload. (kafka-event.md §5)
 * 봉투(EventMessage)가 eventId/eventType/occurredAt 을 담으므로 payload 는 도메인 필드만 둔다.
 */
public record ProductDeletedPayload(
	UUID productId
) {
	public static ProductDeletedPayload of(UUID productId) {
		return new ProductDeletedPayload(productId);
	}
}
