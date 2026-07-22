package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderProductStatus;

import java.util.UUID;

public record OrderListProductProjection(
	UUID orderId,
	UUID orderProductId,
	UUID productId,
	OrderProductStatus orderProductStatus,
	int productAmount,
	boolean downloaded,
	String productType,
	String title,
	String model,
	Double rating
) {
}
