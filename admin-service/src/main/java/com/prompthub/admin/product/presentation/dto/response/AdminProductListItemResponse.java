package com.prompthub.admin.product.presentation.dto.response;

import com.prompthub.admin.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminProductListItemResponse(
	UUID productId,
	String title,
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
			product.getSellerId(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			product.getCreatedAt()
		);
	}
}
