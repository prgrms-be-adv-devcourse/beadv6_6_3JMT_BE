package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "관리자 주문 목록 항목 응답")
public record AdminOrderListResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문에 포함된 판매자 수", example = "2")
	int sellerCount,
	@Schema(description = "판매자별 주문 상품 수와 금액 요약")
	List<SellerSummary> sellers,
	@Schema(description = "상품명. 주문 상품이 여러 건이면 첫 상품명 외 N건 형식", example = "면접 답변 프롬프트 외 2건")
	String productTitle,
	@Schema(description = "주문 상품 수", example = "3")
	int totalOrderCount,
	@Schema(description = "총 주문 금액", example = "15000")
	int totalOrderAmount,
	@Schema(description = "주문 상태", example = "COMPLETED")
	OrderStatus orderStatus,
	@Schema(description = "주문 생성 일시", example = "2026-06-24T10:00:00")
	LocalDateTime createdAt
) {
	@Schema(description = "판매자별 주문 상품 요약")
	public record SellerSummary(
		@Schema(description = "판매자 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222")
		UUID sellerId,
		@Schema(description = "판매자 닉네임", example = "prompt-seller")
		String sellerNickname,
		@Schema(description = "해당 판매자의 주문 상품 수", example = "2")
		int productCount,
		@Schema(description = "해당 판매자의 주문 상품 금액", example = "30000")
		int orderAmount
	) {
	}
}
