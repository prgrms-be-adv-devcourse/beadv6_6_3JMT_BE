package com.prompthub.order.presentation;

import com.prompthub.order.application.usecase.OrderUseCase;
import com.prompthub.presentation.dto.ApiResponse;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.OrderReviewRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import com.prompthub.presentation.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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

	@GetMapping("/{orderId}")
	public ApiResponse<OrderDetailResponse> getOrderDetail(
		@RequestHeader("X-User-Id") UUID buyerId,
		@PathVariable UUID orderId
	) {
		return ApiResponse.success(orderUseCase.getOrderDetail(buyerId, orderId));
	}

	@GetMapping("/{orderId}/content/{orderProductId}")
	public ApiResponse<OrderContentResponse> getOrderContent(
		@RequestHeader("X-User-Id") UUID buyerId,
		@PathVariable UUID orderId,
		@PathVariable UUID orderProductId
	) {
		return ApiResponse.success(orderUseCase.getOrderContent(buyerId, orderId, orderProductId));
	}

	@PostMapping("/review")
	public ApiResponse<Void> upsertReview(
		@RequestHeader("X-User-Id") UUID buyerId,
		@Valid @RequestBody OrderReviewRequest request
	) {
		orderUseCase.upsertReview(buyerId, request);
		return ApiResponse.success();
	}

	@GetMapping
	public PageResponse<OrderListResponse> getOrders(
		@RequestHeader("X-User-Id") UUID buyerId,
		@ModelAttribute PageRequestParams request
	) {
		PageRequestParams resolvedRequest = request.resolve();
		Page<OrderListResponse> orders = orderUseCase.getOrders(buyerId, resolvedRequest);

		return PageResponse.success(
			orders.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orders.getTotalElements(),
			orders.hasNext()
		);
	}

	@GetMapping("/payments")
	public PageResponse<OrderPaymentListResponse> getOrderPayments(
		@RequestHeader("X-User-Id") UUID buyerId,
		@ModelAttribute PageRequestParams request
	) {
		PageRequestParams resolvedRequest = request.resolve();
		Page<OrderPaymentListResponse> orderPayments = orderUseCase.getOrderPayments(buyerId, resolvedRequest);

		return PageResponse.success(
			orderPayments.getContent(),
			resolvedRequest.page(),
			resolvedRequest.size(),
			orderPayments.getTotalElements(),
			orderPayments.hasNext()
		);
	}
}
