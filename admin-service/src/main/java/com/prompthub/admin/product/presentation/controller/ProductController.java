package com.prompthub.admin.product.presentation.controller;

import com.prompthub.admin.product.application.usecase.ProductUseCase;
import com.prompthub.admin.product.presentation.dto.request.ProductRejectRequest;
import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import com.prompthub.presentation.dto.ApiResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/products")
@RequiredArgsConstructor
public class ProductController {

	private final ProductUseCase productUseCase;

	@GetMapping
	public ApiResult<List<AdminProductListItemResponse>> getPendingReviewProducts() {
		return ApiResult.success(productUseCase.getPendingReviewProducts());
	}

	@PatchMapping("/{productId}/approve")
	public ApiResult<Void> approveProduct(
		@PathVariable UUID productId
	) {
		productUseCase.approveProduct(productId);
		return ApiResult.success(null);
	}

	@PatchMapping("/{productId}/reject")
	public ApiResult<Void> rejectProduct(
		@PathVariable UUID productId,
		@Valid @RequestBody ProductRejectRequest request
	) {
		productUseCase.rejectProduct(productId, request.reason());
		return ApiResult.success(null);
	}

	@PatchMapping("/{productId}/revert")
	public ApiResult<Void> revertProductToPendingReview(
		@PathVariable UUID productId
	) {
		productUseCase.revertProductToPendingReview(productId);
		return ApiResult.success(null);
	}
}
