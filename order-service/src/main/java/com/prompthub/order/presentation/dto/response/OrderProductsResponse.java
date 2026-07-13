package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "주문 상품 응답")
public record OrderProductsResponse(
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "상품 ID", example = "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
	UUID productId,
	@Schema(description = "판매자 ID", example = "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678")
	UUID sellerId,
	@Schema(description = "주문 시점 상품 제목 스냅샷", example = "면접 준비 프롬프트")
	String productTitleSnapshot,
	@Schema(description = "주문 시점 상품 유형 스냅샷", example = "PROMPT")
	String productTypeSnapshot,
	@Schema(description = "주문 시점 상품 모델명/분류 스냅샷", example = "GPT-4", nullable = true)
	String productModelSnapshot,
	@Schema(description = "주문 시점 상품 금액 스냅샷. 원 단위 정수", example = "15000")
	int productAmountSnapshot,
	@Schema(description = "주문 상품 상태", example = "PENDING")
	OrderProductStatus orderStatus
) {
}
