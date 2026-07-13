package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.CreateOrderRefundRequest;
import com.prompthub.order.presentation.dto.response.OrderRefundResponse;

import java.util.UUID;

public interface OrderRefundUseCase {

	OrderRefundResponse requestRefund(
		UUID buyerId,
		UUID orderId,
		CreateOrderRefundRequest request
	);
}
