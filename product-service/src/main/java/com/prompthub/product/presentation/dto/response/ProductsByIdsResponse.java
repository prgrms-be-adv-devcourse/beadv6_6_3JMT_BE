package com.prompthub.product.presentation.dto.response;

import java.util.UUID;

public record ProductsByIdsResponse(
	UUID productId,
	UUID sellerId,
	String title,
	int amount,
	String thumbnailUrl,
	String category,
	String model,
	int salesCount,
	double averageRating,
	String status
) {
}
