package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.util.List;
import java.util.UUID;

public interface ProductQueryUseCase {

	PageResponse<ProductListItemResponse> getProducts(String q, String productType, String sort, int page, int size);

	ProductDetailResponse getProduct(UUID productId);

	List<ProductListItemResponse> getRelatedProducts(UUID productId, int limit);

	List<ProductReviewResponse> getProductReviews(UUID productId);
}
