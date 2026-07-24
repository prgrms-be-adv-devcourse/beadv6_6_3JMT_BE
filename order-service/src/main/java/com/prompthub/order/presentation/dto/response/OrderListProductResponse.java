package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "주문 목록의 주문 상품 응답")
public record OrderListProductResponse(
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "상품 ID", example = "e1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
	UUID productId,
	@Schema(description = "주문 상품 상태. PENDING, PAID, FAILED, REFUND_REQUESTED, REFUNDED", example = "PAID")
	OrderProductStatus orderProductStatus,
	@Schema(description = "주문 시점 상품 금액 스냅샷. 원 단위 정수", example = "15000")
	int amount,
	@Schema(description = "환불 가능 여부", example = "true")
	boolean isRefundable,
	@Schema(description = "다운로드 여부", example = "false")
	boolean downloaded,
	@Schema(description = "상품 유형. 주문 목록 조회에서는 제공하지 않아 null", nullable = true)
	String productType,
	@Schema(description = "주문 시점 상품 제목", example = "면접 준비 프롬프트")
	String title,
	@Schema(description = "상품 모델명 또는 분류. 주문 목록 조회에서는 제공하지 않아 null", nullable = true)
	String model,
	@Schema(description = "리뷰 평점. 리뷰 기능을 제공하지 않아 null", nullable = true)
	Double rating
) {
}
