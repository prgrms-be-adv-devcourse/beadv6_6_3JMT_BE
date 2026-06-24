package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "주문 결제 내역 항목 응답")
public record OrderPaymentListResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "결제 ID", example = "3f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a9999")
	UUID paymentId,
	@Schema(description = "결제 상태. PENDING, PAID, FAILED, CANCELED, REFUNDED", example = "PAID")
	PaymentStatus paymentStatus,
	@Schema(description = "환불 여부", example = "false")
	boolean isRefund,
	@Schema(description = "상품 유형", example = "PROMPT")
	String productType,
	@Schema(description = "상품 제목", example = "면접 준비 프롬프트")
	String title,
	@Schema(description = "결제 금액. 원 단위 정수", example = "15000")
	int amount,
	@Schema(description = "결제 완료 일시. yyyy-MM-dd'T'HH:mm:ss 형식, 미결제이면 null", example = "2026-06-18T14:35:00", nullable = true)
	LocalDateTime paidAt
) {
}
