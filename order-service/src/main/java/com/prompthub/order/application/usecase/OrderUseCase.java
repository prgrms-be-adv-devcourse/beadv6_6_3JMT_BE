package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentListResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OrderUseCase {

	CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request);

	OrderDetailResponse getOrderDetail(UUID buyerId, UUID orderId);

	OrderContentResponse getOrderContent(UUID buyerId, UUID orderId, UUID orderProductId);

	Page<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request);

	Page<OrderPaymentListResponse> getOrderPayments(UUID buyerId, PageRequestParams request);
}
