package com.prompthub.product.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "상품 수정 응답")
public record ProductUpdateResponse(
	@Schema(description = "상품 ID")
	UUID productId,

	@Schema(description = "수정 반영 후 버전(major.patch)", example = "2.1")
	String version,

	@Schema(description = "수정 반영 후 상태", example = "ON_SALE")
	String status
) {
}
