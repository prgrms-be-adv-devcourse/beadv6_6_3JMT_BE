package com.prompthub.order.application.usecase;

import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;

import java.util.UUID;

public interface OrderUseCase {

	CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request);

	PageResponse<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request);

	PageResponse<OrderPaymentListResponse> getOrderPayments(UUID buyerId, PageRequestParams request);
}
