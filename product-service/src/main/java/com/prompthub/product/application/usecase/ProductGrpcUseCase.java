package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import java.util.List;
import java.util.UUID;

public interface ProductGrpcUseCase {

	List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds);

	List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds);

	ProductContentResponse getProductContent(UUID productId);
}
