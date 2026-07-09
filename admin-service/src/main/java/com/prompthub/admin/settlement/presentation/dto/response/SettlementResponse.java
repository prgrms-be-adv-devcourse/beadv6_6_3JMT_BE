package com.prompthub.admin.settlement.presentation.dto.response;

import com.prompthub.admin.settlement.domain.model.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "정산 단건 응답")
public record SettlementResponse(
	@Schema(description = "정산 ID(UUID)")
	UUID settlementId,

	@Schema(description = "판매자 ID(UUID)")
	UUID sellerId,

	@Schema(description = "정산 표시 상태", example = "CANCELLED")
	String displayStatus,

	@Schema(description = "취소 시각(취소된 경우)", example = "2026-06-24T09:00:00")
	LocalDateTime cancelledAt
) {

	public static SettlementResponse from(Settlement settlement) {
		return new SettlementResponse(
			settlement.getSettlementId(),
			settlement.getSellerId(),
			settlement.displayStatus().name(),
			settlement.getCancelledAt());
	}
}
