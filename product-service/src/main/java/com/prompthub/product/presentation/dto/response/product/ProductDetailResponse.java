package com.prompthub.product.presentation.dto.response.product;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductDetailResponse(
	UUID id,
	String title,
	String productType,
	String model,
	int amount,
	double rating,
	int salesCount,
	UUID sellerId,
	int sellerProductCount,
	String desc,
	String thumbnail_url,
	List<String> imageUrls,
	String content,
	List<String> tags,
	List<ProductVersionResponse> versions,
	boolean hasContext,
	boolean hasObjective,
	boolean hasNuance,
	boolean hasTone,
	boolean hasExamples,
	boolean hasExecution,
	boolean hasRoleAssignment,
	boolean checklistRecorded,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
