package com.prompthub.admin.settlement.dto.response;

import com.prompthub.admin.settlement.entity.Settlement;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.dto.response.SettlementMonthlyResponse.StatusCount;
import com.prompthub.admin.settlement.dto.response.SettlementMonthlyResponse.WeeklySettlement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "어드민 판매자 월별 정산 상세 응답")
public record SettlementDetailResponse(
        @Schema(description = "판매자 ID(UUID)")
        UUID sellerId,

        @Schema(description = "판매자명", nullable = true)
        String sellerName,

        @Schema(description = "정산 월(YYYY-MM)", example = "2026-07")
        String settlementMonth,

        @Schema(description = "월에 포함된 전체 주간 정산 건수", example = "4")
        long weeklySettlementCount,

        @Schema(description = "합계에 반영된 비취소 주간 정산 건수", example = "3")
        long aggregatedSettlementCount,

        @Schema(description = "비취소 판매 건수 합계", example = "22")
        long salesCount,

        @Schema(description = "비취소 총 거래액", example = "2200000.00")
        BigDecimal grossAmount,

        @Schema(description = "비취소 판매 수수료", example = "330000.00")
        BigDecimal feeAmount,

        @Schema(description = "비취소 환불 차감액", example = "100000.00")
        BigDecimal refundAmount,

        @Schema(description = "비취소 지급 예정 또는 완료 금액", example = "1770000.00")
        BigDecimal payoutAmount,

        @Schema(description = "주간 정산 상태별 건수")
        List<StatusCount> statusCounts,

        @Schema(description = "기간 시작일 오름차순 주간 정산")
        List<WeeklySettlement> weeklySettlements) {

    public static SettlementDetailResponse from(
            MonthlyAggregate aggregate,
            List<MonthlyStatusCount> counts,
            List<Settlement> weeklySettlements,
            String sellerName) {
        return new SettlementDetailResponse(
                aggregate.key().sellerId(),
                sellerName,
                aggregate.key().settlementMonth().toString(),
                aggregate.weeklySettlementCount(),
                aggregate.aggregatedSettlementCount(),
                aggregate.salesCount(),
                aggregate.grossAmount(),
                aggregate.feeAmount(),
                aggregate.refundAmount(),
                aggregate.payoutAmount(),
                SettlementMonthlyResponse.statusCounts(aggregate.key(), counts),
                weeklySettlements.stream().map(WeeklySettlement::from).toList());
    }
}
