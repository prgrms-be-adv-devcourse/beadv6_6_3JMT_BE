package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "구매 콘텐츠 열람 응답")
public record OrderContentResponse(
	@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
	UUID orderId,
	@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
	UUID orderProductId,
	@Schema(description = "주문 번호", example = "ORD-20260618-000001")
	String orderNumber,
	@Schema(description = "상품 ID", example = "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
	UUID productId,
	@Schema(description = "다운로드형 상품 여부", example = "true")
	boolean isDownload,
	@Schema(description = "상품 제목", example = "면접 준비 프롬프트")
	String productTitle,
	@Schema(description = "구매 후 열람 가능한 콘텐츠 원문", example = "구매 후 확인 가능한 프롬프트 원문")
	String content
) {
}
