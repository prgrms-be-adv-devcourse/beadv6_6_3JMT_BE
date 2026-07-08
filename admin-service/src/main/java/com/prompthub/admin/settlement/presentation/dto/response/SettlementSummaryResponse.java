package com.prompthub.admin.settlement.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "정산 요약 카드 응답")
public record SettlementSummaryResponse(
	@Schema(description = "상태별 요약 카드 목록")
	List<Card> cards
) {

	@Schema(description = "정산 요약 카드")
	public record Card(
		@Schema(description = "표시 상태", example = "WAITING")
		String status,

		@Schema(description = "지급액 합계", example = "1135500.00")
		BigDecimal totalAmount,

		@Schema(description = "건수", example = "4")
		long count
	) {
	}
}
