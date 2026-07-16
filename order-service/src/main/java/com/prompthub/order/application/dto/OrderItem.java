package com.prompthub.order.application.dto;

import java.util.UUID;

public record OrderItem(
	UUID productId,
	UUID sellerId,
	String productTitle,
	int amount
) {
}
