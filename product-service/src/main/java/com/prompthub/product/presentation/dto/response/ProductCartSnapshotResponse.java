package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record ProductCartSnapshotResponse(
	UUID productId,
	String title,
	String productType,
	int amount,
	String thumbnailUrl,
	UUID sellerId,
	String sellerNickname,
	String status
) {

	public static ProductCartSnapshotResponse from(Product product, String sellerNickname) {
		return new ProductCartSnapshotResponse(
			product.getId(),
			product.getName(),
			product.getProductType(),
			product.getAmount(),
			product.getThumbnailUrl(),
			product.getSellerId(),
			sellerNickname,
			product.getStatus().name()
		);
	}
}
