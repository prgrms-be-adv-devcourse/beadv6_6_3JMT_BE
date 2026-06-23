package com.prompthub.order.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddCartProductRequest(
	@NotNull(message = "상품 ID는 null일 수 없습니다.")
	UUID productId
) {
}
