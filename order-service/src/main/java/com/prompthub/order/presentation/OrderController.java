package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.presentation.dto.ApiResponse;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

	private final OrderUseCase orderUseCase;

	@PostMapping
	public ApiResponse<CreateOrderResponse> createOrder(
		@RequestHeader("X-User-Id") UUID buyerId,
		@Valid @RequestBody CreateOrderRequest request
	) {
		return ApiResponse.success(orderUseCase.createOrder(buyerId, request));
	}

	@GetMapping
	public PageResponse<OrderListResponse> getOrders(
		@RequestHeader("X-User-Id") UUID buyerId,
		@ModelAttribute PageRequestParams request
	) {
		return orderUseCase.getOrders(buyerId, request.resolve());
	}
}
