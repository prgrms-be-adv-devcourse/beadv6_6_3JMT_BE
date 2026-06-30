package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sellers/me")
@RequiredArgsConstructor
public class SellerDashboardController {

	private final ProductSellerUseCase productSellerUseCase;

	@GetMapping("/products")
	public ApiResult<List<SellerProductListItemResponse>> getMyProducts(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role
	) {
		return ApiResult.success(productSellerUseCase.getMyProducts(sellerId));
	}

	@GetMapping("/products/{productId}")
	public ApiResult<SellerProductDetailResponse> getMyProduct(
		@RequestHeader("X-User-Id") UUID sellerId,
		@RequestHeader("X-User-Role") String role,
		@PathVariable UUID productId
	) {
		return ApiResult.success(productSellerUseCase.getMyProduct(sellerId, productId));
	}
}
