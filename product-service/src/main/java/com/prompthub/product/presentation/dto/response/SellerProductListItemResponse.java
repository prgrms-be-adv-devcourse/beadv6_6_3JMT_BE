package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record SellerProductListItemResponse(
	UUID productId,
	String title,
	String category,
	String model,
	int amount,
	String status,
	int salesCount,
	String thumbnailUrl,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	public static SellerProductListItemResponse from(Product product) {
		return new SellerProductListItemResponse(
			product.getId(),
			product.getName(),
			product.getCategory() != null ? product.getCategory().getCode() : null,
			product.getProductType(),
			product.getAmount(),
			product.getStatus().name(),
			product.getSalesCount(),
			product.getThumbnailUrl(),
			product.getCreatedAt(),
			product.getUpdatedAt()
		);
	}
}
