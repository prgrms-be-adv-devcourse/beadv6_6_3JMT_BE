package com.prompthub.admin.product.application.usecase;

import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.UUID;

public interface ProductUseCase {

	List<AdminProductListItemResponse> getPendingReviewProducts();

	void approveProduct(UUID productId);

	void rejectProduct(UUID productId, String reason);

	void revertProductToPendingReview(UUID productId);
}
