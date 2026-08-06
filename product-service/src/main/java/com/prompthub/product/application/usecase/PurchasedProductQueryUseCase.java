package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.UUID;

public interface PurchasedProductQueryUseCase {

	PurchasedProductDetailResponse getPurchasedProduct(UUID userId, UUID productId);
}
