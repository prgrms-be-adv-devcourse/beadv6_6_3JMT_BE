package com.prompthub.product.application.usecase.integration;

import com.prompthub.product.presentation.dto.response.product.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.product.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.product.ProductOrderSnapshotResponse;
import java.util.List;
import java.util.UUID;

public interface ProductGrpcUseCase {

	List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds);

	List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds);

	ProductContentResponse getProductContent(UUID productId);

	List<ProductListItemResponse> getPersonalizedRecommendations(
		List<UUID> cartProductIds, List<UUID> purchasedProductIds, int limit);
}
