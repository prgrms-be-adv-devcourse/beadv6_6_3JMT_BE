package com.prompthub.ai.settlement.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 판매자 정산 집계 조회 포트다. 모델 경계로 protobuf message나 원본 식별자를 노출하지 않는다.
 */
public interface SellerSettlementQuery {

    SettlementSummaryResult getSummary(UUID actorId, String periodType, String period);

    SettlementComparisonResult comparePeriods(
            UUID actorId,
            String periodType,
            String currentPeriod,
            String comparisonPeriod);

    WeeklyBreakdownResult getWeeklyBreakdown(UUID actorId, String month);

    PayoutStatusResult getPayoutStatus(UUID actorId, String settlementMonth);

    record SettlementAggregateResult(
            String includedStartDate,
            String includedEndDate,
            String dataThrough,
            boolean partial,
            long saleCount,
            long refundCount,
            String grossSaleAmount,
            String grossRefundAmount,
            String saleFeeAmount,
            String refundedFeeAmount,
            String netFeeAmount,
            String payoutAmount
    ) {
    }

    record CountChangeResult(
            long difference,
            String changeRatePercent,
            boolean comparable
    ) {
    }

    record DecimalChangeResult(
            String difference,
            String changeRatePercent,
            boolean comparable
    ) {
    }

    record SettlementAggregateComparisonResult(
            CountChangeResult saleCount,
            CountChangeResult refundCount,
            DecimalChangeResult grossSaleAmount,
            DecimalChangeResult grossRefundAmount,
            DecimalChangeResult saleFeeAmount,
            DecimalChangeResult refundedFeeAmount,
            DecimalChangeResult netFeeAmount,
            DecimalChangeResult payoutAmount
    ) {
    }

    record SettlementSummaryResult(
            String periodType,
            String requestedPeriod,
            SettlementAggregateResult aggregate
    ) {
    }

    record SettlementComparisonResult(
            String periodType,
            String currentPeriod,
            String comparisonPeriod,
            SettlementAggregateResult current,
            SettlementAggregateResult comparison,
            SettlementAggregateComparisonResult changes
    ) {
    }

    record WeeklySettlementBucketResult(
            String weekStartDate,
            String weekEndDate,
            boolean boundaryWeek,
            SettlementAggregateResult aggregate
    ) {
    }

    record WeeklyBreakdownResult(
            String requestedMonth,
            boolean partial,
            String dataThrough,
            List<WeeklySettlementBucketResult> weeks
    ) {
        public WeeklyBreakdownResult {
            weeks = List.copyOf(Objects.requireNonNull(weeks, "weeks"));
        }
    }

    record PayoutStatusCountResult(
            String status,
            long count
    ) {
    }

    record WeeklyPayoutStatusResult(
            String periodStartDate,
            String periodEndDate,
            String status,
            String paidAt
    ) {
    }

    record PayoutStatusResult(
            String settlementMonth,
            List<PayoutStatusCountResult> statusCounts,
            List<WeeklyPayoutStatusResult> weeklySettlements
    ) {
        public PayoutStatusResult {
            statusCounts = List.copyOf(Objects.requireNonNull(statusCounts, "statusCounts"));
            weeklySettlements = List.copyOf(Objects.requireNonNull(weeklySettlements, "weeklySettlements"));
        }
    }
}
