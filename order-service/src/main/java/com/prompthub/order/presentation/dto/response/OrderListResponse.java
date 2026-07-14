package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "주문 목록 항목 응답")
public record OrderListResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "상품 ID", example = "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
	UUID productId,
	@Schema(description = "주문 상태. PENDING, PAID, FAILED, CANCELED, PARTIALLY_REFUNDED, REFUNDED", example = "PAID")
	OrderStatus orderStatus,
	@Schema(description = "환불 가능 여부", example = "true")
	boolean isRefundable,
	@Schema(description = "상품 유형", example = "PROMPT")
	String productType,
	@Schema(description = "상품 제목", example = "면접 준비 프롬프트")
	String title,
	@Schema(description = "상품 모델명 또는 분류. 없을 수 있음", example = "GPT-4.1", nullable = true)
	String model,
	@Schema(description = "리뷰 평점. 리뷰가 없으면 null", example = "4.5", nullable = true)
	Double rating,
	// String thumbnailUrl,
	@Schema(description = "결제 완료 일시. yyyy-MM-dd'T'HH:mm:ss 형식, 미결제이면 null", example = "2026-06-18T14:35:00", nullable = true)
	LocalDateTime paidAt,
	@Schema(description = "주문 생성 일시. yyyy-MM-dd'T'HH:mm:ss 형식", example = "2026-06-18T14:30:00")
	LocalDateTime createdAt
) {
}
