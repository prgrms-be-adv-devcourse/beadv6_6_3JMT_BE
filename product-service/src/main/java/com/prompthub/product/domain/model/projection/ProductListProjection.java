package com.prompthub.product.domain.model.projection;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductListProjection(
	UUID id,
	String title,
	String productType,
	String model,
	int amount,
	double rating,
	int salesCount,
	UUID sellerId,
	String description,
	String thumbnailUrl,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
