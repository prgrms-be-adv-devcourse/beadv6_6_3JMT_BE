package com.prompthub.user.sellersettlement.domain.repository;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface SellerSettlementAnalysisQueryRepository {

    AnalysisAggregate aggregate(UUID sellerId, AnalysisQueryRange range);

    List<WeeklyAnalysisAggregate> findWeeklyBreakdown(
            UUID sellerId,
            YearMonth month,
            LocalDate includedEnd,
            LocalDate completedThrough);

    PayoutStatusSnapshot findPayoutStatuses(UUID sellerId, YearMonth settlementMonth);

    record AnalysisQueryRange(
            LocalDate includedStart,
            LocalDate includedEnd,
            LocalDate completedThrough) {

        public boolean hasIncludedDays() {
            return includedEnd != null && !includedEnd.isBefore(includedStart);
        }
    }

    record AnalysisAggregate(
            long saleCount,
            long refundCount,
            BigDecimal grossSaleAmount,
            BigDecimal grossRefundAmount,
            BigDecimal saleFeeAmount,
            BigDecimal refundedFeeAmount,
            BigDecimal netFeeAmount,
            BigDecimal payoutAmount) {

        public static AnalysisAggregate zero() {
            return new AnalysisAggregate(
                    0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    record WeeklyAnalysisAggregate(
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDate includedStart,
            LocalDate includedEnd,
            boolean boundaryWeek,
            AnalysisAggregate aggregate) {
    }

    record PayoutStatusRow(
            LocalDate periodStart,
            LocalDate periodEnd,
            SettlementDisplayStatus status,
            LocalDateTime paidAt) {
    }

    record PayoutStatusCount(SettlementDisplayStatus status, long count) {
    }

    record PayoutStatusSnapshot(
            List<PayoutStatusCount> counts,
            List<PayoutStatusRow> weeklySettlements) {

        public PayoutStatusSnapshot {
            counts = List.copyOf(counts);
            weeklySettlements = List.copyOf(weeklySettlements);
        }
    }
}
