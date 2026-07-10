package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

/**
 * PRODUCT_PRICE_CHANGED 이벤트 payload. (kafka-event.md §5)
 * 봉투(EventMessage)가 eventId/eventType/occurredAt 을 담으므로 payload 는 도메인 필드만 둔다.
 * 필드명(productId/previousPrice/changedPrice)은 소비측 직렬화 계약이므로 변경하지 않는다.
 */
public record ProductPriceChangedPayload(
	UUID productId,
	int previousPrice,
	int changedPrice
) {
	public static ProductPriceChangedPayload of(UUID productId, int previousPrice, int changedPrice) {
		return new ProductPriceChangedPayload(productId, previousPrice, changedPrice);
	}
}
