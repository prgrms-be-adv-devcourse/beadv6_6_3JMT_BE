package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;

import java.util.UUID;

public record OrderProductsResponse(
	UUID orderProductId,
	UUID productId,
	UUID sellerId,
	String productTitleSnapshot,
	String productTypeSnapshot,
	int productAmountSnapshot,
	OrderStatus orderStatus
) {
}
