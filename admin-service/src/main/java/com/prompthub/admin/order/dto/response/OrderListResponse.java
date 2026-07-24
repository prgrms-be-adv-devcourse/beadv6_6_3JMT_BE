package com.prompthub.admin.order.dto.response;

import com.prompthub.admin.order.entity.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "관리자 주문 목록 항목 응답")
public record OrderListResponse(
	@Schema(description = "주문 번호", example = "ORD-20260724-0001")
	String orderNumber,
	@Schema(description = "구매자 정보")
	UserSummary buyer,
	@Schema(description = "총 주문 금액", example = "15000")
	int totalOrderAmount,
	@Schema(description = "주문 상태", example = "COMPLETED")
	OrderStatus orderStatus,
	@Schema(description = "주문 일시", example = "2026-06-24T10:00:00")
	LocalDateTime orderedAt,
	@Schema(description = "주문 상품 목록")
	List<OrderProductSummary> orderProducts
) {
	@Schema(description = "사용자 요약 정보")
	public record UserSummary(
		@Schema(description = "사용자 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111") UUID userId,
		@Schema(description = "사용자 이름", example = "prompt-user") String name,
		@Schema(description = "프로필 이미지 URL", example = "https://cdn.example.com/profile.png") String profileImageUrl
	) {
	}

	@Schema(description = "주문 상품 요약")
	public record OrderProductSummary(
		@Schema(description = "판매자 정보") UserSummary seller,
		@Schema(description = "상품 제목", example = "면접 답변 프롬프트") String productTitle,
		@Schema(description = "상품 주문 금액", example = "15000") int productAmount,
		@Schema(
			description = "주문 상품 상태",
			example = "PAID",
			allowableValues = {"PENDING", "PAID", "FAILED", "REFUND_REQUESTED", "REFUNDED"}
		) String orderProductStatus
	) {
	}
}
