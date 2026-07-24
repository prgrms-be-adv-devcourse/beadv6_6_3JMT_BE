package com.prompthub.admin.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "관리자 거래 통계 조회 기간 응답")
public record TransactionPeriodResponse(
	@Schema(description = "조회 시작일", example = "2026-06-18")
	LocalDate startDate,
	@Schema(description = "조회 종료일", example = "2026-06-24")
	LocalDate endDate
) {
}
