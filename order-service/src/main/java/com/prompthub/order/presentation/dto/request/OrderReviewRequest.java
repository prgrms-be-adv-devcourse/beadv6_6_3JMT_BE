package com.prompthub.order.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OrderReviewRequest(
	@NotNull(message = "상품 ID는 null일 수 없습니다.")
	UUID productId,

	@NotNull(message = "평점은 null일 수 없습니다.")
	@Min(value = 1, message = "평점은 1 이상이어야 합니다.")
	@Max(value = 5, message = "평점은 5 이하여야 합니다.")
	Integer rating
) {
}
