package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.presentation.dto.ApiResult;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductController {

	private final ProductQueryUseCase productQueryUseCase;

	@GetMapping("/products")
	public PageResponse<ProductListItemResponse> getProducts(
		@RequestParam(defaultValue = "") String q,
		@RequestParam(defaultValue = "all") String category,
		@RequestParam(defaultValue = "popular") String sort,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return productQueryUseCase.getProducts(q, category, sort, page, size);
	}

	@GetMapping("/products/{productId}")
	public ApiResult<ProductDetailResponse> getProduct(@PathVariable UUID productId) {
		return ApiResult.success(productQueryUseCase.getProduct(productId));
	}

	@GetMapping("/products/{productId}/related")
	public ApiResult<List<ProductListItemResponse>> getRelatedProducts(
		@PathVariable UUID productId,
		@RequestParam(defaultValue = "4") int limit
	) {
		return ApiResult.success(productQueryUseCase.getRelatedProducts(productId, limit));
	}

	@GetMapping("/products/{productId}/reviews")
	public ApiResult<List<ProductReviewResponse>> getProductReviews(@PathVariable UUID productId) {
		return ApiResult.success(productQueryUseCase.getProductReviews(productId));
	}
}
