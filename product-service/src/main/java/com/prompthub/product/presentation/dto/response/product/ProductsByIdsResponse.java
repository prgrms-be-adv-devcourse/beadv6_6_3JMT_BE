package com.prompthub.product.presentation.dto.response.product;

import java.util.UUID;

public record ProductsByIdsResponse(
	UUID productId,
	UUID sellerId,
	String title,
	int amount,
	String thumbnailUrl,
	String productType,
	String model,
	int salesCount,
	double averageRating,
	String status
) {
}
