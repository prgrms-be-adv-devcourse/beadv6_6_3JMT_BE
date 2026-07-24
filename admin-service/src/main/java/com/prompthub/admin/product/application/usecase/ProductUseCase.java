package com.prompthub.admin.product.application.usecase;

import com.prompthub.admin.product.application.dto.AdminProductListQuery;
import com.prompthub.admin.product.application.dto.AdminProductPageResult;
import java.util.UUID;

public interface ProductUseCase {

	AdminProductPageResult listProducts(AdminProductListQuery query);

	void approveProduct(UUID productId);

	void rejectProduct(UUID productId, String reason);

	void revertProductToPendingReview(UUID productId);
}
