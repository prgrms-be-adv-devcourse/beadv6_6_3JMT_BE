package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "관리자 일자별 거래 통계 응답")
public record DailyTransactionResponse(
	@Schema(description = "거래 일자", example = "2026-06-24")
	LocalDate date,
	@Schema(description = "결제 승인 완료 주문 수", example = "5")
	long transactionCount,
	@Schema(description = "실제 거래액", example = "120000")
	long transactionAmount
) {
}
