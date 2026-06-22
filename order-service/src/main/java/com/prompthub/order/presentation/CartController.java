package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import com.prompthub.presentation.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

	private final CartUseCase cartUseCase;

	@GetMapping
	public ApiResponse<CartResponse> getCart(
		@RequestHeader("X-User-Id") UUID buyerId
	) {
		return ApiResponse.success(cartUseCase.getCart(buyerId));
	}

	@PostMapping("/products")
	public ApiResponse<AddCartProductResponse> addCartProduct(
		@RequestHeader("X-User-Id") UUID buyerId,
		@Valid @RequestBody AddCartProductRequest request
	) {
		return ApiResponse.success(cartUseCase.addCartProduct(buyerId, request));
	}

	@DeleteMapping("/products/{cartProductId}")
	public ApiResponse<Void> deleteCartProduct(
		@RequestHeader("X-User-Id") UUID buyerId,
		@PathVariable UUID cartProductId
	) {
		cartUseCase.deleteCartProduct(buyerId, cartProductId);
		return ApiResponse.success();
	}
}
