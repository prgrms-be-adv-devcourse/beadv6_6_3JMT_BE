package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "관리자 최근 7일 거래량 응답")
public record WeeklyTransactionResponse(
	@Schema(description = "최근 7일 결제 승인 완료 주문 수", example = "42")
	long totalTransactionCount,
	@Schema(description = "최근 7일 실제 거래액", example = "980000")
	long totalTransactionAmount,
	TransactionPeriodResponse period,
	List<DailyTransactionResponse> dailyTransactions
) {
}
