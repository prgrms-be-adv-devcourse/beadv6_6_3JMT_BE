package com.prompthub.product.application.usecase.purchase;

import com.prompthub.product.presentation.dto.response.purchase.PurchasedProductDetailResponse;
import java.util.UUID;

public interface PurchasedProductQueryUseCase {

	PurchasedProductDetailResponse getPurchasedProduct(UUID userId, UUID productId);
}
