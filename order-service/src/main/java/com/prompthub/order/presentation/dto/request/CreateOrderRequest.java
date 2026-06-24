package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
	@Schema(description = "주문할 상품 ID 목록", example = "[\"p1b55b60-5e84-4f3f-b4f1-6c10e1a22222\"]", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotEmpty(message = "주문 상품 목록은 비어 있을 수 없습니다.")
	List<@NotNull(message = "상품 ID는 null일 수 없습니다.") UUID> productIds
) {
}
