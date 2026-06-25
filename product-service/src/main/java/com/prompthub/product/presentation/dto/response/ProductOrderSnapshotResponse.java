package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductOrderSnapshotResponse(
	UUID productId,
	UUID sellerId,
	String title,
	String productType,
	int amount
) {

	public static ProductOrderSnapshotResponse from(Product product) {
		return new ProductOrderSnapshotResponse(
			product.getId(),
			product.getSellerId(),
			product.getName(),
			product.getProductType(),
			product.getAmount()
		);
	}
}
