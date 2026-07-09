package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "주문 결제 가능 여부 응답")
public record OrderPaymentValidationResponse(
	@Schema(description = "결제 가능 여부", example = "true")
	boolean payable,
	@Schema(description = "주문 ID")
	UUID orderId,
	@Schema(description = "구매자 ID")
	UUID buyerId,
	@Schema(description = "주문 총 금액", example = "30000")
	int totalAmount,
	@Schema(description = "결제 만료 시각")
	LocalDateTime expiresAt
) {
}
