package com.prompthub.product.presentation.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductListItemResponse(
	UUID id,
	String title,
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
	List<String> tags,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
