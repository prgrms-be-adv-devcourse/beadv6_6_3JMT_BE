package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.presentation.dto.request.InternalReviewUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

	private final ProductInternalUseCase productInternalUseCase;

	@PostMapping("/reviews")
	public void upsertReview(
		@RequestBody InternalReviewUpsertRequest request
	) {
		productInternalUseCase.upsertReview(request.buyerId(), request.productId(), request.rating());
	}
}
