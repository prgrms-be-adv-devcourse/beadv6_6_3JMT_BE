package com.prompthub.order.application.event.order;

import java.util.UUID;

public record OrderPaidProduct(
	UUID orderProductId,
	UUID productId,
	UUID sellerId,
	String productTitle,
	String productType,
	int productAmount
) {
}
