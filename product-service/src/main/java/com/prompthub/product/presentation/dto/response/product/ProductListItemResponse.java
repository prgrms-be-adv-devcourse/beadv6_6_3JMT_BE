package com.prompthub.product.presentation.dto.response.product;

import com.prompthub.product.domain.model.projection.ProductListProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductListItemResponse(
	UUID id,
	String title,
	String productType,
	String model,
	int amount,
	double rating,
	int salesCount,
	UUID sellerId,
	String desc,
	String thumbnail_url,
	List<String> tags,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public static ProductListItemResponse from(
		ProductListProjection product,
		String thumbnailUrl,
		List<String> tags
	) {
		return new ProductListItemResponse(
			product.id(),
			product.title(),
			product.productType(),
			product.model(),
			product.amount(),
			product.rating(),
			product.salesCount(),
			product.sellerId(),
			product.description(),
			thumbnailUrl,
			tags,
			product.createdAt(),
			product.updatedAt()
		);
	}
}
