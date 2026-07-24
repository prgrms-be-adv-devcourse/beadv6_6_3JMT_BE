package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "장바구니 조회 응답")
public record CartResponse(
	@Schema(description = "장바구니 ID. 장바구니가 없으면 null", example = "00000000-0000-0000-0000-000000000700", nullable = true)
	UUID cartId,
	@Schema(description = "구매자 ID", example = "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234")
	UUID buyerId,
	@Schema(description = "장바구니 상품 목록")
	List<CartProductResponse> products,
	@Schema(description = "장바구니 총 금액. 원 단위 정수", example = "15000")
	int totalAmount,
	@Schema(description = "장바구니 상품 수", example = "1")
	int totalItemCount
) {
}
