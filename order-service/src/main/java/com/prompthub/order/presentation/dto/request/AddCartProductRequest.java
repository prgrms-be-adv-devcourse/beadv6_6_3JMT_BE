package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "장바구니 상품 추가 요청")
public record AddCartProductRequest(
	@Schema(description = "장바구니에 추가할 상품 ID", example = "a1b55b60-5e84-4f3f-b4f1-6c10e1a22222", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "상품 ID는 null일 수 없습니다.")
	UUID productId
) {
}
