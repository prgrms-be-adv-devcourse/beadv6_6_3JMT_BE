package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "주문상품 다운로드 확정 응답")
public record OrderProductDownloadResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "상품이 다운로드되었는지 여부", example = "true")
	boolean downloaded,
	@Schema(description = "환불 가능 여부", example = "false")
	boolean isRefundable
) {
}
