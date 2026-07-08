package com.prompthub.user.sellersettlement.presentation.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "정산 상태 변경 응답")
public record SellerSettlementStatusResponse(
        @Schema(description = "정산 ID(UUID)")
        UUID settlementId,

        @Schema(description = "표시 상태 코드", example = "PAYOUT_REQUESTED")
        String status,

        @Schema(description = "표시 상태 라벨(한글)", example = "지급 신청")
        String statusLabel,

        @Schema(description = "승인 시각")
        LocalDateTime approvedAt,

        @Schema(description = "지급 신청 시각")
        LocalDateTime payoutRequestedAt,

        @Schema(description = "지급 완료 시각")
        LocalDateTime paidAt,

        @Schema(description = "취소 시각")
        LocalDateTime cancelledAt
) {

    public static SellerSettlementStatusResponse from(SellerSettlementResult result) {
        return new SellerSettlementStatusResponse(
                result.settlementId(),
                result.status().name(),
                result.status().getLabel(),
                result.approvedAt(),
                result.payoutRequestedAt(),
                result.paidAt(),
                result.cancelledAt());
    }
}
