package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public record SellerProductListItemResponse(
	UUID productId,
	String title,
	String productType,
	String model,
	int amount,
	String status,
	int salesCount,
	String thumbnailUrl,
	String rejectionReason,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	public static SellerProductListItemResponse from(Product product, int familySalesCount, StorageClient storageClient) {
		return new SellerProductListItemResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			familySalesCount,
			toUrl(product.getThumbnailUrl(), storageClient),
			product.getRejectionReason(),
			product.getCreatedAt(),
			product.getUpdatedAt()
		);
	}

	private static String toUrl(String key, StorageClient storageClient) {
		if (key == null || key.isBlank()) return null;
		return storageClient.generatePresignedDownloadUrl(key);
	}
}
