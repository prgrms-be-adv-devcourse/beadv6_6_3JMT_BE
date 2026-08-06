package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductCartSnapshotResponse(
	UUID productId,
	UUID sellerId,
	String productTitle,
	String productType,
	int productAmount,
	String thumbnailUrl,
	String sellerNickname,
	String productStatus
) {

	public static ProductCartSnapshotResponse from(UUID productId, Product product, String sellerNickname) {
		return new ProductCartSnapshotResponse(
			productId,
			product.getSellerId(),
			product.getName(),
			product.getProductType().name(),
			product.getAmount(),
			product.getThumbnailUrl(),
			sellerNickname,
			product.getStatus().name()
		);
	}
}
