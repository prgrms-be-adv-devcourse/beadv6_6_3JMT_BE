package com.prompthub.product.application.usecase.query;

import com.prompthub.product.presentation.dto.response.product.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.review.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.product.ProductsByIdsResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.util.List;
import java.util.UUID;

public interface ProductQueryUseCase {

	PageResponse<ProductListItemResponse> getProducts(String q, String productType, String sort, int page, int size);

	List<String> suggest(String q);

	ProductDetailResponse getProduct(UUID productId);

	List<ProductListItemResponse> getRecommendedProducts(UUID productId, int limit);

	List<ProductReviewResponse> getProductReviews(UUID productId);

	List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds);
}
