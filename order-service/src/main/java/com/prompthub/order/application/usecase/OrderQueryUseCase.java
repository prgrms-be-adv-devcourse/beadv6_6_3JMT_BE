package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.presentation.dto.request.PageRequestParams;
import com.prompthub.order.presentation.dto.response.OrderContentResponse;
import com.prompthub.order.presentation.dto.response.OrderDetailResponse;
import com.prompthub.order.presentation.dto.response.OrderListResponse;
import com.prompthub.order.presentation.dto.response.OrderPaymentValidationResponse;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderQueryUseCase {

	OrderForPaymentResult getOrderForPayment(UUID orderId);

	OrderDetailResponse getOrderDetail(UUID buyerId, UUID orderId);

	OrderContentResponse getOrderContent(UUID buyerId, UUID orderId, UUID orderProductId);

	OrderPaymentValidationResponse validatePaymentReady(UUID buyerId, UUID orderId, int amount, LocalDateTime now);

	Page<OrderListResponse> getOrders(UUID buyerId, PageRequestParams request);

	boolean hasAccessiblePaidProduct(UUID buyerId, UUID productId);

	List<UUID> getAccessiblePaidProductIds(UUID buyerId);

}
