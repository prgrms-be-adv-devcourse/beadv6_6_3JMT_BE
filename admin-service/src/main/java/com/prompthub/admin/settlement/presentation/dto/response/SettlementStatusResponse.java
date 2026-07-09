package com.prompthub.admin.settlement.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "정산 상태 변경 응답")
public record SettlementStatusResponse(
	@Schema(description = "정산 ID(UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
	UUID settlementId,

	@Schema(description = "정산 표시 상태", example = "APPROVED")
	SettlementDisplayStatus displayStatus,

	@Schema(description = "승인 시각", example = "2026-06-24T09:00:00")
	LocalDateTime approvedAt,

	@Schema(description = "지급 완료 시각", example = "2026-06-24T15:00:00")
	LocalDateTime paidAt,

	@Schema(description = "취소 시각", example = "2026-06-24T09:00:00")
	LocalDateTime cancelledAt,

	@Schema(description = "최종 수정 시각", example = "2026-06-24T09:00:00")
	LocalDateTime updatedAt
) {

	public static SettlementStatusResponse from(Settlement settlement) {
		return new SettlementStatusResponse(
			settlement.getSettlementId(),
			settlement.displayStatus(),
			settlement.getApprovedAt(),
			settlement.getPaidAt(),
			settlement.getCancelledAt(),
			settlement.getUpdatedAt());
	}
}
