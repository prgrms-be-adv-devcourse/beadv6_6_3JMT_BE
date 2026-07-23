package com.prompthub.order.application.dto;

import java.util.UUID;

public record OrderCreationItem(
	UUID productId,
	UUID sellerId,
	String productTitle,
	int amount
) {
}
