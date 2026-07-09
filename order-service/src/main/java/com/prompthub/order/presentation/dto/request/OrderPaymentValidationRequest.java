package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "주문 결제 가능 여부 검증 요청")
public record OrderPaymentValidationRequest(
	@Schema(description = "결제 요청 금액", example = "30000", requiredMode = Schema.RequiredMode.REQUIRED)
	@Positive(message = "결제 금액은 양수여야 합니다.")
	int amount
) {
}
