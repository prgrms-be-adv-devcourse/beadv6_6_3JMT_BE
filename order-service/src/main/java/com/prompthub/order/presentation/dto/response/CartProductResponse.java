package com.prompthub.order.presentation.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record CartProductResponse(
	UUID cartProductId,
	UUID productId,
	String productTitle,
	String productType,
	int productAmount,
	String thumbnailUrl,
	UUID sellerId,
	String sellerNickname,
	String productStatus,
	LocalDateTime addedAt
) {
}
