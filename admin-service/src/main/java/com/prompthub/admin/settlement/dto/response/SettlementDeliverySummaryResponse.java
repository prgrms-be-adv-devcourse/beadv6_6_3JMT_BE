package com.prompthub.admin.settlement.dto.response;

import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "어드민 정산 전달 상태 요약 응답")
public record SettlementDeliverySummaryResponse(
        @Schema(description = "전달 대기 건수", example = "2")
        long calculatedCount,

        @Schema(description = "정상 대사 건수", example = "8")
        long reconciledCount,

        @Schema(description = "전달 실패 건수", example = "3")
        long deliveryFailedCount,

        @Schema(description = "데이터 불일치 건수", example = "1")
        long mismatchCount,

        @Schema(description = "수동 재전송 실행 중 건수", example = "1")
        long retryInProgressCount) {

    public static SettlementDeliverySummaryResponse from(
            Map<SettlementDeliveryStatus, Long> counts,
            long retryInProgressCount) {
        return new SettlementDeliverySummaryResponse(
                count(counts, SettlementDeliveryStatus.CALCULATED),
                count(counts, SettlementDeliveryStatus.RECONCILED),
                count(counts, SettlementDeliveryStatus.DELIVERY_FAILED),
                count(counts, SettlementDeliveryStatus.MISMATCH),
                retryInProgressCount);
    }

    private static long count(
            Map<SettlementDeliveryStatus, Long> counts,
            SettlementDeliveryStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}
