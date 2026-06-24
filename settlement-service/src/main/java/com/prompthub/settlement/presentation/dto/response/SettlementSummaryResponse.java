package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
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

	public static SettlementSummaryResponse from(SettlementSummaryResult result) {
		List<Card> cards = result.cards().stream()
			.map(card -> new Card(card.status().name(), card.totalAmount(), card.count()))
			.toList();
		return new SettlementSummaryResponse(cards);
	}
}
