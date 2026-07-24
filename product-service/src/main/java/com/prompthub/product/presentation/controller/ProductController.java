package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.request.ProductsByIdsRequest;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.presentation.dto.ApiResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class ProductController {

	private final ProductQueryUseCase productQueryUseCase;
	private final ProductSellerUseCase productSellerUseCase;

	@GetMapping("/products")
	public PageResponse<ProductListItemResponse> getProducts(
		@RequestParam(defaultValue = "") String q,
		@RequestParam(defaultValue = "all") String productType,
		@RequestParam(defaultValue = "popular") String sort,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return productQueryUseCase.getProducts(q, productType, sort, page, size);
	}

	@PostMapping("/products")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResult<ProductCreateResponse> createProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@Valid @RequestBody ProductCreateRequest request
	) {
		return ApiResult.success(productSellerUseCase.createProduct(sellerId, request));
	}

	@PostMapping("/products/wishlists")
	public ApiResult<List<ProductsByIdsResponse>> getProductsByIds(@Valid @RequestBody ProductsByIdsRequest request) {
		return ApiResult.success(productQueryUseCase.getProductsByIds(request.productIds()));
	}

	@PostMapping("/products/orders")
	public ApiResult<List<ProductsByIdsResponse>> getProductsForOrders(@Valid @RequestBody ProductsByIdsRequest request) {
		return ApiResult.success(productQueryUseCase.getProductsByIds(request.productIds()));
	}

	@GetMapping("/products/sellers/me")
	public ApiResult<List<SellerProductListItemResponse>> getMyProducts(
		@RequestHeader("X-User-Id") UUID sellerId
	) {
		return ApiResult.success(productSellerUseCase.getMyProducts(sellerId));
	}

	@GetMapping("/products/sellers/me/summary")
	public ApiResult<ProductCountResponse> getMyProductSummary(
		@RequestHeader("X-User-Id") UUID sellerId
	) {
		return ApiResult.success(productSellerUseCase.getProductCount(sellerId));
	}

	@GetMapping("/products/{productId}")
	public ApiResult<ProductDetailResponse> getProduct(@PathVariable UUID productId) {
		return ApiResult.success(productQueryUseCase.getProduct(productId));
	}

	@PatchMapping("/products/{productId}")
	public ApiResult<Void> updateProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@PathVariable UUID productId,
		@Valid @RequestBody ProductUpdateRequest request
	) {
		productSellerUseCase.updateProduct(sellerId, productId, request);
		return ApiResult.success(null);
	}

	@DeleteMapping("/products/{productId}")
	public ApiResult<Void> deleteProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@PathVariable UUID productId
	) {
		productSellerUseCase.deleteProduct(sellerId, productId);
		return ApiResult.success(null);
	}

	@GetMapping("/products/{productId}/recommends")
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

	@PatchMapping("/products/{productId}/inspection")
	public ApiResult<Void> submitForReview(
		@RequestHeader("X-User-Id") UUID sellerId,
		@PathVariable UUID productId
	) {
		productSellerUseCase.submitForReview(sellerId, productId);
		return ApiResult.success(null);
	}

	@GetMapping("/products/{productId}/sellers/me")
	public ApiResult<SellerProductDetailResponse> getMyProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@PathVariable UUID productId
	) {
		return ApiResult.success(productSellerUseCase.getMyProduct(sellerId, productId));
	}
}
