package com.prompthub.product.presentation.dto.response.seller;

import com.prompthub.product.domain.model.entity.Product;
import java.util.List;
import java.util.UUID;

public record SellerProductDetailResponse(
	UUID productId,
	String title,
	String productType,
	String model,
	int amount,
	String desc,
	String content,
	String fileUrl,
	String fileObjectKey,
	String externalUrl,
	String status,
	String version,
	double averageRating,
	String thumbnailUrl,
	String thumbnailObjectKey,
	List<String> imageUrls,
	List<String> imageObjectKeys,
	List<String> tags,
	String liveVersion,
	List<SellerProductVersionResponse> versions
) {
	public static SellerProductDetailResponse from(
		Product product,
		Product liveOnSale,
		List<Product> historyMembers,
		double averageRating,
		String thumbnailUrl,
		List<String> imageUrls,
		String fileUrl
	) {
		return new SellerProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getDescription(),
			product.getContent(),
			fileUrl,
			product.getFileUrl(),
			product.getExternalUrl(),
			product.getStatus().name(),
			product.getMajorVersion() + "." + product.getPatchVersion(),
			averageRating,
			thumbnailUrl,
			product.getThumbnailUrl(),
			imageUrls,
			product.getImageUrls(),
			product.getTags(),
			liveOnSale != null ? liveOnSale.getMajorVersion() + "." + liveOnSale.getPatchVersion() : null,
			historyMembers.stream().map(SellerProductVersionResponse::from).toList()
		);
	}
}
