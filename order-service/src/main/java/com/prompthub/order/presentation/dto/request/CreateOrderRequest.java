package com.prompthub.order.presentation.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
	@NotEmpty(message = "주문 상품 목록은 비어 있을 수 없습니다.")
	List<@NotNull(message = "상품 ID는 null일 수 없습니다.") UUID> productIds
) {
}