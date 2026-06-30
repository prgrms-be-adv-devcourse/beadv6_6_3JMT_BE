package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import java.util.List;
import java.util.UUID;

public interface ProductInternalUseCase {

	List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds);

	List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds);

	ProductCartSnapshotResponse getCartSnapshot(UUID productId);

	List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds);

	ProductContentResponse getProductContent(UUID productId);

	void upsertReview(UUID buyerId, UUID productId, Integer rating);

	ProductCountResponse getProductCount(UUID sellerId);
}
