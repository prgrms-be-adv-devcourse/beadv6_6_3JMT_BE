package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductOrderSnapshotResponse(
	UUID productId,
	UUID sellerId,
	String title,
	String productType,
	int amount,
	String model
) {

	public static ProductOrderSnapshotResponse from(Product product) {
		return new ProductOrderSnapshotResponse(
			product.getId(),
			product.getSellerId(),
			product.getName(),
			product.getProductType().name(),
			product.getAmount(),
			product.getModel() != null ? product.getModel() : ""
		);
	}
}
