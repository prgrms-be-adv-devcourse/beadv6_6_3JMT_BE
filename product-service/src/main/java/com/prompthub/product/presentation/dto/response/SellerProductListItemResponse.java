package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record SellerProductListItemResponse(
	UUID productId,
	String title,
	String category,
	String productType,
	String model,
	int amount,
	String status,
	int salesCount,
	String thumbnailUrl,
	String rejectionReason,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	public static SellerProductListItemResponse from(Product product) {
		return new SellerProductListItemResponse(
			product.getId(),
			product.getName(),
			product.getCategory() != null ? product.getCategory().getCode() : null,
			product.getProductType(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			product.getSalesCount(),
			product.getThumbnailUrl(),
			product.getRejectionReason(),
			product.getCreatedAt(),
			product.getUpdatedAt()
		);
	}
}
