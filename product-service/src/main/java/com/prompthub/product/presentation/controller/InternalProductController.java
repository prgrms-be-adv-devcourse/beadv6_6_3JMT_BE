package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.presentation.dto.request.InternalReviewUpsertRequest;
import com.prompthub.product.presentation.dto.request.ProductIdsRequest;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotsResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

	private final ProductInternalUseCase productInternalUseCase;

	@PostMapping("/order-snapshots")
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(
		@RequestBody ProductIdsRequest request
	) {
		return productInternalUseCase.getOrderSnapshots(request.productIds());
	}

	@GetMapping("/{productId}/cart-snapshot")
	public ProductCartSnapshotResponse getCartSnapshot(
		@PathVariable UUID productId
	) {
		return productInternalUseCase.getCartSnapshot(productId);
	}

	@PostMapping("/cart-snapshots")
	public ProductCartSnapshotsResponse getCartSnapshots(
		@RequestBody ProductIdsRequest request
	) {
		return new ProductCartSnapshotsResponse(productInternalUseCase.getCartSnapshots(request.productIds()));
	}

	@GetMapping("/{productId}/content")
	public ProductContentResponse getProductContent(
		@PathVariable UUID productId
	) {
		return productInternalUseCase.getProductContent(productId);
	}

	@PostMapping("/reviews")
	public void upsertReview(
		@RequestBody InternalReviewUpsertRequest request
	) {
		productInternalUseCase.upsertReview(request.buyerId(), request.productId(), request.rating());
	}

	@GetMapping("/count")
	public ProductCountResponse getProductCount(
		@RequestParam UUID sellerId
	) {
		return productInternalUseCase.getProductCount(sellerId);
	}
}
