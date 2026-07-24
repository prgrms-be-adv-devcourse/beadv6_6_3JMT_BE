package com.prompthub.admin.product.presentation.controller;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.product.application.dto.AdminProductListQuery;
import com.prompthub.admin.product.application.dto.AdminProductPageResult;
import com.prompthub.admin.product.application.usecase.ProductUseCase;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.presentation.dto.request.ProductRejectRequest;
import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/products")
@RequiredArgsConstructor
public class ProductController {

	private final ProductUseCase productUseCase;

	@GetMapping
	public PageResponse<AdminProductListItemResponse> listProducts(
		@RequestParam(defaultValue = "ALL") String status,
		@RequestParam(required = false) String keyword,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		AdminProductListQuery query = new AdminProductListQuery(parseStatusFilter(status), keyword, page, size);
		AdminProductPageResult result = productUseCase.listProducts(query);
		return PageResponse.success(result.items(), result.page(), result.size(), result.total(), result.hasNext());
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

	// 목록 필터용 — "ALL"은 필터 없음(null)으로 취급 (UserController와 동일 계약).
	private static ProductStatus parseStatusFilter(String statusParam) {
		return switch (statusParam) {
			case "pending_review" -> ProductStatus.PENDING_REVIEW;
			case "on_sale" -> ProductStatus.ON_SALE;
			case "rejected" -> ProductStatus.REJECTED;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}
}
