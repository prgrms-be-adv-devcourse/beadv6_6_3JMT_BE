package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "주문 상품 리뷰 생성 또는 수정 요청")
public record OrderReviewRequest(
	@Schema(description = "리뷰를 작성할 상품 ID", example = "a1b55b60-5e84-4f3f-b4f1-6c10e1a22222", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "상품 ID는 null일 수 없습니다.")
	UUID productId,

	@Schema(description = "리뷰 평점. 1 이상 5 이하 정수", example = "4", minimum = "1", maximum = "5", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotNull(message = "평점은 null일 수 없습니다.")
	@Min(value = 1, message = "평점은 1 이상이어야 합니다.")
	@Max(value = 5, message = "평점은 5 이하여야 합니다.")
	Integer rating
) {
}
