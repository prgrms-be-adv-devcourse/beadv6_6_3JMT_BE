package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "주문 상세 응답")
public record OrderDetailResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문 번호", example = "ORD-20260618-000001")
	String orderNumber,
	@Schema(description = "구매자 ID", example = "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234")
	UUID buyerId,
	@Schema(description = "주문 상태. PENDING, PAID, FAILED, CANCELED, REFUNDED", example = "PAID")
	OrderStatus orderStatus,
	@Schema(description = "주문 상품 목록")
	List<OrderDetailProductResponse> products,
	@Schema(description = "주문 총 금액. 원 단위 정수", example = "30000")
	int totalAmount,
	@Schema(description = "주문 상품 수", example = "2")
	int totalProductCount,
	@Schema(description = "결제 완료 일시. yyyy-MM-dd'T'HH:mm:ss 형식, 미결제이면 null", example = "2026-06-18T14:35:00", nullable = true)
	LocalDateTime paidAt,
	@Schema(description = "취소 일시. 취소 전이면 null", example = "2026-06-19T09:10:00", nullable = true)
	LocalDateTime canceledAt,
	@Schema(description = "환불 일시. 환불 전이면 null", example = "2026-06-20T11:20:00", nullable = true)
	LocalDateTime refundedAt,
	@Schema(description = "주문 생성 일시. yyyy-MM-dd'T'HH:mm:ss 형식", example = "2026-06-18T14:30:00")
	LocalDateTime createdAt,
	@Schema(description = "다운로드형 상품 포함 여부", example = "true")
	boolean hasDownloadProduct
) {
}
