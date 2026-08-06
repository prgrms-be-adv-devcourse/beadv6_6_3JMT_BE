package com.prompthub.user.sellersettlement.presentation.dto.response;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyKey;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class SellerSettlementMonthlyResponse {

    private SellerSettlementMonthlyResponse() {
    }

    @Schema(description = "월별 정산 상태별 건수")
    public record StatusCount(
            @Schema(description = "표시 상태 코드", example = "APPROVED")
            String status,

            @Schema(description = "표시 상태 라벨", example = "승인")
            String statusLabel,

            @Schema(description = "해당 상태의 주간 정산 건수", example = "2")
            long count) {

        public static StatusCount from(MonthlyStatusCount count) {
            return new StatusCount(
                    count.status().name(), count.status().getLabel(), count.count());
        }
    }

    public static List<StatusCount> statusCounts(
            MonthlyKey key, List<MonthlyStatusCount> allCounts) {
        return allCounts.stream()
                .filter(count -> count.key().equals(key))
                .sorted(Comparator.comparingInt(count -> count.status().ordinal()))
                .map(StatusCount::from)
                .toList();
    }

    @Schema(description = "주간 정산에서 수행 가능한 액션")
    public record Action(
            @Schema(description = "액션 타입", example = "REQUEST_PAYOUT")
            String type,

            @Schema(description = "액션 라벨", example = "지급 신청하기")
            String label) {

        static Action requestPayout() {
            return new Action("REQUEST_PAYOUT", "지급 신청하기");
        }
    }

    @Schema(description = "월별 상세의 주간 정산")
    public record WeeklySettlement(
            @Schema(description = "정산 ID(UUID)")
            UUID settlementId,

            @Schema(description = "정산 기간 시작", example = "2026-06-29")
            LocalDate periodStart,

            @Schema(description = "정산 기간 종료", example = "2026-07-05")
            LocalDate periodEnd,

            @Schema(description = "판매 건수", example = "22")
            int salesCount,

            @Schema(description = "총 거래액", example = "2200000.00")
            BigDecimal grossAmount,

            @Schema(description = "판매 수수료", example = "330000.00")
            BigDecimal feeAmount,

            @Schema(description = "환불 차감액", example = "100000.00")
            BigDecimal refundAmount,

            @Schema(description = "지급 예정 또는 완료 금액", example = "1770000.00")
            BigDecimal payoutAmount,

            @Schema(description = "표시 상태 코드", example = "APPROVED")
            String status,

            @Schema(description = "표시 상태 라벨", example = "승인")
            String statusLabel,

            @Schema(description = "정산 계산 시각")
            LocalDateTime calculatedAt,

            @Schema(description = "승인 시각", nullable = true)
            LocalDateTime approvedAt,

            @Schema(description = "지급 신청 시각", nullable = true)
            LocalDateTime payoutRequestedAt,

            @Schema(description = "지급 완료 시각", nullable = true)
            LocalDateTime paidAt,

            @Schema(description = "취소 시각", nullable = true)
            LocalDateTime cancelledAt,

            @Schema(description = "현재 상태에서 수행 가능한 액션")
            List<Action> availableActions) {

        public static WeeklySettlement from(SellerSettlement settlement) {
            List<Action> actions = settlement.canRequestPayout()
                    ? List.of(Action.requestPayout()) : List.of();
            return new WeeklySettlement(
                    settlement.getSettlementId(),
                    settlement.getPeriodStart(),
                    settlement.getPeriodEnd(),
                    settlement.getProductCount(),
                    settlement.getTotalAmount(),
                    settlement.getFeeTotalAmount(),
                    zeroIfNull(settlement.getRefundAmount()),
                    settlement.getSettlementTotalAmount(),
                    settlement.getStatus().name(),
                    settlement.getStatus().getLabel(),
                    settlement.getCalculatedAt(),
                    settlement.getApprovedAt(),
                    settlement.getPayoutRequestedAt(),
                    settlement.getPaidAt(),
                    settlement.getCancelledAt(),
                    actions);
        }
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
