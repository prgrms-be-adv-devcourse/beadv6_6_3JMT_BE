package com.prompthub.product.presentation.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductListItemResponse(
	UUID id,
	String title,
	String category,
	String icon,
	String productType,
	String model,
	int amount,
	Integer originalAmount,
	double rating,
	int salesCount,
	String seller,
	UUID sellerId,
	String badge,
	String desc,
	String thumbnail_url,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
