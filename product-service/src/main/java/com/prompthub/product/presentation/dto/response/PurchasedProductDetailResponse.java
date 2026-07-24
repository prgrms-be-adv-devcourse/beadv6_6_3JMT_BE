package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.UUID;

public record PurchasedProductDetailResponse(
	UUID productId,
	String title,
	String productType,
	String model,
	String content,
	String fileUrl,
	String externalUrl,
	String thumbnailUrl,
	UUID sellerId,
	double averageRating,
	Integer myRating
) {

	public static PurchasedProductDetailResponse of(
		UUID requestedId, Product product,
		String content, String fileUrl, String externalUrl,
		double averageRating, Integer myRating
	) {
		return new PurchasedProductDetailResponse(
			requestedId,
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			content,
			fileUrl,
			externalUrl,
			product.getThumbnailUrl(),
			product.getSellerId(),
			averageRating,
			myRating
		);
	}
}
