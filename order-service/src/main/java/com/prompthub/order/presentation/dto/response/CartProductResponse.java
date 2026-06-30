package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "장바구니 상품 응답")
public record CartProductResponse(
	@Schema(description = "장바구니 상품 ID", example = "00000000-0000-0000-0000-000000000701")
	UUID cartProductId,
	@Schema(description = "상품 ID", example = "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
	UUID productId,
	@Schema(description = "상품 제목", example = "면접 준비 프롬프트")
	String productTitle,
	@Schema(description = "상품 유형", example = "PROMPT")
	String productType,
	@Schema(description = "상품 금액. 원 단위 정수", example = "15000")
	int productAmount,
	@Schema(description = "썸네일 URL. 없을 수 있음", example = "https://cdn.prompthub.com/products/prompt-thumb.png", nullable = true)
	String thumbnailUrl,
	@Schema(description = "판매자 ID", example = "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678")
	UUID sellerId,
	@Schema(description = "판매자 닉네임", example = "prompt-seller")
	String sellerNickname,
	@Schema(description = "상품 판매 상태", example = "ON_SALE")
	String productStatus,
	@Schema(description = "장바구니 추가 일시. yyyy-MM-dd'T'HH:mm:ss 형식", example = "2026-06-22T10:00:00")
	LocalDateTime addedAt
) {
}
