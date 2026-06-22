package com.prompthub.order.application.dto;

import java.util.UUID;

public record ProductCartSnapshot(
	UUID productId,
	String title,
	String productType,
	int amount,
	String thumbnailUrl,
	UUID sellerId,
	String sellerNickname,
	String status
) {
}
