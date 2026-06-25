package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.UUID;

public interface ProductAdminUseCase {

	List<AdminProductListItemResponse> getPendingReviewProducts(String role);

	void approveProduct(String role, UUID productId);

	void rejectProduct(String role, UUID productId, String reason);

	void revertProductToPendingReview(String role, UUID productId);
}
