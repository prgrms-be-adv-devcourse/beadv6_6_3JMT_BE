package com.prompthub.order.application.dto;

import java.util.UUID;

public record ProductOrderSnapshot(
	UUID productId,
	UUID sellerId,
	String title,
	String productType,
	int amount
) {
}
