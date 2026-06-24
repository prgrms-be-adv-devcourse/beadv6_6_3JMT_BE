package com.prompthub.settlement.presentation.dto.request;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.YearMonth;
import java.util.UUID;

@Schema(description = "정산 배치잡 실행 요청")
public record RunSettlementJobRequest(
	@Schema(description = "정산 대상 월 (YYYY-MM)", example = "2026-06")
	@NotNull YearMonth period
) {

	public RunSettlementJobCommand toCommand(UUID actorId) {
		return RunSettlementJobCommand.manual(period, actorId);
	}
}
