package com.prompthub.settlement.presentation.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "정산 배치잡 실행 요청")
public record RunSettlementBatchRequest(
	@Schema(description = "정산 포함 시작일인 월요일", example = "2026-07-13")
	@NotNull LocalDate periodStart,
	@Schema(description = "정산 포함 종료일인 일요일", example = "2026-07-19")
	@NotNull LocalDate periodEnd
) {

	@JsonIgnore
	@AssertTrue(message = "정산 기간은 월요일부터 일요일까지여야 합니다.")
	public boolean isWeeklyPeriod() {
		if (periodStart == null || periodEnd == null) {
			return true;
		}
		return periodStart.getDayOfWeek() == DayOfWeek.MONDAY
			&& periodEnd.equals(periodStart.plusDays(6));
	}

	public RunSettlementBatchCommand toCommand(UUID actorId) {
		return RunSettlementBatchCommand.manual(SettlementPeriod.of(periodStart, periodEnd), actorId);
	}
}
