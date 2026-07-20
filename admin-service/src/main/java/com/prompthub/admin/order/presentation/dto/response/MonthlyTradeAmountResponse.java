package com.prompthub.admin.order.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 월간 실제 거래액 응답")
public record MonthlyTradeAmountResponse(
	@Schema(description = "이번 달 실제 거래액", example = "1250000")
	long monthlyTransactionAmount
) {
}
