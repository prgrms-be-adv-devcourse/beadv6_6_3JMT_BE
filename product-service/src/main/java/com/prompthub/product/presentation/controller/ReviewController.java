package com.prompthub.product.presentation.controller;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.presentation.dto.request.ReviewUpsertRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class ReviewController {

	private final ProductInternalUseCase productInternalUseCase;

	@PostMapping("/products/{productId}/reviews")
	public ApiResult<Void> upsertReview(
		@RequestHeader("X-User-Id") UUID buyerId,
		@PathVariable UUID productId,
		@Valid @RequestBody ReviewUpsertRequest request
	) {
		productInternalUseCase.upsertReview(buyerId, productId, request.rating());
		return ApiResult.success();
	}
}
