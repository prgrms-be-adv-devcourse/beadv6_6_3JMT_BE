package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.AddCartProductRequest;
import com.prompthub.order.presentation.dto.response.AddCartProductResponse;
import com.prompthub.order.presentation.dto.response.CartResponse;

import java.util.UUID;

public interface CartUseCase {

	CartResponse getCart(UUID buyerId);

	AddCartProductResponse addCartProduct(UUID buyerId, AddCartProductRequest request);

	void deleteCartProduct(UUID buyerId, UUID cartProductId);
}
