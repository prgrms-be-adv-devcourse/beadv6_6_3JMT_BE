package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.ProductGrpcUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductGrpcService implements ProductGrpcUseCase {

	private final ProductFamilyResolver productFamilyResolver;
	private final StorageClient storageClient;

	@Override
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductOrderSnapshotResponse.from(id, resolved.get(id)))
			.toList();
	}

	@Override
	public List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductCartSnapshotResponse.from(id, resolved.get(id), null))
			.toList();
	}

	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		return new ProductContentResponse(productId, resolveDeliverable(product));
	}

	private String resolveDeliverable(Product product) {
		return switch (product.getProductType()) {
			case PROMPT -> product.getContent();
			case PPT, EXCEL -> presignIfPresent(product.getFileUrl());
			case NOTION -> product.getExternalUrl();
		};
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}
}
