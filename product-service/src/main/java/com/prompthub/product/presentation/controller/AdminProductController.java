package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.usecase.ProductAdminUseCase;
import com.prompthub.product.presentation.dto.request.ProductRejectRequest;
import com.prompthub.product.presentation.dto.response.AdminProductListItemResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

	private final ProductAdminUseCase productAdminUseCase;

	@GetMapping
	public ApiResult<List<AdminProductListItemResponse>> getPendingReviewProducts(
		@RequestHeader(value = "X-User-Role", required = false) String role
	) {
		return ApiResult.success(productAdminUseCase.getPendingReviewProducts(role));
	}

	@PatchMapping("/{productId}/approve")
	public ApiResult<Void> approveProduct(
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId
	) {
		productAdminUseCase.approveProduct(role, productId);
		return ApiResult.success(null);
	}

	@PatchMapping("/{productId}/reject")
	public ApiResult<Void> rejectProduct(
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId,
		@Valid @RequestBody ProductRejectRequest request
	) {
		productAdminUseCase.rejectProduct(role, productId, request.reason());
		return ApiResult.success(null);
	}

	@PatchMapping("/{productId}/revert")
	public ApiResult<Void> revertProductToPendingReview(
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId
	) {
		productAdminUseCase.revertProductToPendingReview(role, productId);
		return ApiResult.success(null);
	}
}
