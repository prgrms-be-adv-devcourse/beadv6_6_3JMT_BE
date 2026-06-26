package com.prompthub.product.presentation.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductDetailResponse(
	UUID id,
	String title,
	String category,
	String icon,
	String model,
	int amount,
	double rating,
	int salesCount,
	String seller,
	UUID sellerId,
	String badge,
	String desc,
	String thumbnail_url,
	String content,
	List<ProductVersionResponse> versions,
	List<String> features,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
