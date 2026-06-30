package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class SellerProductController {

	private final ProductSellerUseCase productSellerUseCase;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResult<ProductCreateResponse> createProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role,
		@Valid @RequestBody ProductCreateRequest request
	) {
		return ApiResult.success(productSellerUseCase.createProduct(sellerId, request));
	}

	@PutMapping("/{productId}")
	public ApiResult<Void> updateProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId,
		@Valid @RequestBody ProductUpdateRequest request
	) {
		productSellerUseCase.updateProduct(sellerId, productId, request);
		return ApiResult.success(null);
	}

	@PatchMapping("/{productId}/submit")
	public ApiResult<Void> submitForReview(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId
	) {
		productSellerUseCase.submitForReview(sellerId, productId);
		return ApiResult.success(null);
	}

	@DeleteMapping("/{productId}")
	public ApiResult<Void> deleteProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId
	) {
		productSellerUseCase.deleteProduct(sellerId, role, productId);
		return ApiResult.success(null);
	}
}
