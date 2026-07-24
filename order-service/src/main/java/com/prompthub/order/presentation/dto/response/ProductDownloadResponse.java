package com.prompthub.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "구매 상품 다운로드 여부 응답")
public record ProductDownloadResponse(
	@Schema(description = "상품이 다운로드되었는지 여부", example = "true")
	boolean downloaded
) {
}
