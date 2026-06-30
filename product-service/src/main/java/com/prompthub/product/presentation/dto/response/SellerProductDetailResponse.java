package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;
import java.util.List;
import java.util.UUID;

public record SellerProductDetailResponse(
	UUID productId,
	String title,
	String category,
	String productType,
	String model,
	int amount,
	String desc,
	String content,
	String status,
	String version,
	String thumbnailUrl,
	List<String> imageUrls,
	List<String> tags
) {
	public static SellerProductDetailResponse from(Product product) {
		return new SellerProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getCategory() != null ? product.getCategory().getCode() : null,
			product.getProductType(),
			product.getModel(),
			product.getAmount(),
			product.getDescription(),
			product.getContent(),
			product.getStatus().name(),
			product.getMajorVersion() + "." + product.getPatchVersion(),
			product.getThumbnailUrl(),
			product.getImageUrls(),
			product.getTags()
		);
	}
}
