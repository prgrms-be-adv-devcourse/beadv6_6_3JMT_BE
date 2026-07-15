package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RefundOrderRequest(
	@Schema(description = "결제 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222")
	@NotNull UUID paymentId,
	@Schema(description = "환불할 주문 상품 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a3333")
	@NotNull UUID orderProductId
) {
}
