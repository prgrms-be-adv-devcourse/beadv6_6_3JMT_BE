package com.prompthub.order.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RefundOrderRequest(
	@NotNull UUID paymentId,
	@NotNull UUID orderProductId
) {
}
