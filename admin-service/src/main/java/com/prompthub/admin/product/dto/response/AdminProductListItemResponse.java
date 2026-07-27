package com.prompthub.admin.product.dto.response;

import com.prompthub.admin.product.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminProductListItemResponse(
	UUID productId,
	String title,
	String sellerNickname,
	String productType,
	String model,
	int amount,
	String status,
	String rejectionReason,
	LocalDateTime createdAt
) {
	public static AdminProductListItemResponse from(Product product, String sellerNickname) {
		return new AdminProductListItemResponse(
			product.getId(),
			product.getName(),
			sellerNickname,
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			product.getRejectionReason(),
			product.getCreatedAt()
		);
	}
}
