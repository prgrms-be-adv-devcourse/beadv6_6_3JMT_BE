package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.application.client.StorageClient;
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
	String externalUrl,
	String status,
	String version,
	String thumbnailUrl,
	List<String> imageUrls,
	List<String> tags,
	String liveVersion,
	List<SellerProductVersionResponse> versions
) {
	public static SellerProductDetailResponse from(
		Product product,
		Product liveOnSale,
		List<Product> historyMembers,
		StorageClient storageClient
	) {
		return new SellerProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getDescription(),
			product.getContent(),
			toUrl(product.getFileUrl(), storageClient),
			product.getExternalUrl(),
			product.getStatus().name(),
			product.getMajorVersion() + "." + product.getPatchVersion(),
			toUrl(product.getThumbnailUrl(), storageClient),
			toUrls(product.getImageUrls(), storageClient),
			product.getTags(),
			liveOnSale != null ? liveOnSale.getMajorVersion() + "." + liveOnSale.getPatchVersion() : null,
			historyMembers.stream().map(SellerProductVersionResponse::from).toList()
		);
	}

	private static String toUrl(String key, StorageClient storageClient) {
		if (key == null || key.isBlank()) return null;
		return storageClient.generatePresignedDownloadUrl(key);
	}

	private static List<String> toUrls(List<String> keys, StorageClient storageClient) {
		if (keys == null || keys.isEmpty()) return List.of();
		return keys.stream()
			.map(key -> storageClient.generatePresignedDownloadUrl(key))
			.toList();
	}
}
