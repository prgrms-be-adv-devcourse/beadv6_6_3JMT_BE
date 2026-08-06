package com.prompthub.order.application.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "환불 요청 접수 결과")
public record RefundResult(
		@Schema(description = "환불 요청 ID", example = "4d8f2c6e-91a2-4b3a-9f99-3f527f7d5678")
		UUID refundRequestId,
		@Schema(description = "환불 대상 주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		UUID orderId,
		@Schema(description = "환불 대상 주문 상품 ID 목록")
		@ArraySchema(minItems = 1, uniqueItems = true,
			schema = @Schema(description = "환불 대상 주문 상품 ID", format = "uuid"))
		List<UUID> orderProductIds,
		@Schema(description = "환불 예정 금액. 원 단위 정수", example = "15000")
		int refundAmount,
		@Schema(description = "환불 요청 상태", allowableValues = {"REQUESTED"}, example = "REQUESTED")
		String status
	) {
	}
