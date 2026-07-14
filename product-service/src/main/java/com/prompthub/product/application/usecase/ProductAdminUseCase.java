package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.UUID;

public interface ProductAdminUseCase {

	List<AdminProductListItemResponse> getPendingReviewProducts();

	void approveProduct(UUID productId);

	void rejectProduct(UUID productId, String reason);

	void revertProductToPendingReview(UUID productId);
}
