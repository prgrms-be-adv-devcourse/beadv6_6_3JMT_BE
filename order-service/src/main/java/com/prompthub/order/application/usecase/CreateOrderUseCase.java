package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;

import java.util.UUID;

public interface CreateOrderUseCase {

	CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request);
}
