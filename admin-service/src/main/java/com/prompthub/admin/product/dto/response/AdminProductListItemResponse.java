package com.prompthub.admin.product.dto.response;

import com.prompthub.admin.product.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "관리자 상품 목록 항목 응답")
public record AdminProductListItemResponse(
	@Schema(description = "상품 ID")
	UUID productId,

	@Schema(description = "상품명")
	String title,

	@Schema(description = "상품 설명")
	String description,

	@Schema(description = "판매자 닉네임")
	String sellerNickname,

	@Schema(description = "상품 유형")
	String productType,

	@Schema(description = "모델명")
	String model,

	@Schema(description = "가격")
	int amount,

	@Schema(description = "상품 상태")
	String status,

	@Schema(description = "반려 사유")
	String rejectionReason,

	@Schema(description = "생성 시각", example = "2026-07-08T11:30:00")
	LocalDateTime createdAt
) {
	public static AdminProductListItemResponse from(Product product, String sellerNickname) {
		return new AdminProductListItemResponse(
			product.getId(),
			product.getName(),
			product.getDescription(),
			sellerNickname,
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			product.getStatus().name(),
			product.getRejectionReason(),
			product.getCreatedAt()
		);
	}
}
