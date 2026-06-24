package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;
import com.prompthub.presentation.dto.ApiResult;
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
	public ApiResult<CartResponse> getCart(
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId
	) {
		return ApiResult.success(cartUseCase.getCart(buyerId));
	}

	@PostMapping("/products")
	public ApiResult<AddCartProductResponse> addCartProduct(
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@Valid @RequestBody AddCartProductRequest request
	) {
		return ApiResult.success(cartUseCase.addCartProduct(buyerId, request));
	}

	@DeleteMapping("/products/{cartProductId}")
	public ApiResult<Void> deleteCartProduct(
		@RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
		@PathVariable UUID cartProductId
	) {
		cartUseCase.deleteCartProduct(buyerId, cartProductId);
		return ApiResult.success();
	}
}
