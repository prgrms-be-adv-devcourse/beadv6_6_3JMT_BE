package com.prompthub.product.application.service.integration;

import com.prompthub.product.application.service.query.ProductFamilyResolver;
import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.integration.ProductGrpcUseCase;
import com.prompthub.product.application.usecase.query.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.vo.ProductDeliverable;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.product.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.product.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.product.ProductOrderSnapshotResponse;
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
	private final ObjectStorageGateway objectStorage;
	private final ProductQueryUseCase productQueryUseCase;

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
		return new ProductContentResponse(productId, createDownloadableValue(product.resolveDeliverable()));
	}

	@Override
	public List<ProductListItemResponse> getPersonalizedRecommendations(
		List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit
	) {
		return productQueryUseCase.getPersonalizedRecommendedProducts(cartProductIds, purchasedProductIds, limit);
	}

	private String createDownloadableValue(ProductDeliverable deliverable) {
		if (deliverable.type() == ProductDeliverable.Type.FILE_OBJECT_KEY) {
			return objectStorage.createPresignedGetUrl(deliverable.value());
		}
		return deliverable.value();
	}
}
