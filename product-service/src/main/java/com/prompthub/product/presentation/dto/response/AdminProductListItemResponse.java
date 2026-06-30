package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminProductListItemResponse(
	UUID productId,
	String title,
	String category,
	UUID sellerId,
	String productType,
	String model,
	int amount,
	String status,
	LocalDateTime createdAt
) {
	public static AdminProductListItemResponse from(Product product) {
		return new AdminProductListItemResponse(
			product.getId(),
			product.getName(),
			product.getCategory() != null ? product.getCategory().getCode() : null,
			product.getSellerId(),
			product.getProductType(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			product.getCreatedAt()
		);
	}
}
