package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementMonthlyQueryRepository {

    MonthlyPage findMonthlyPage(
            SettlementDisplayStatus status,
            YearMonth settlementMonth,
            int page,
            int size);

    Optional<MonthlyAggregate> findMonthlyAggregate(
            UUID sellerId, YearMonth settlementMonth);

    List<MonthlyStatusCount> findStatusCounts(List<MonthlyKey> keys);

    List<Settlement> findWeeklySettlements(UUID sellerId, YearMonth settlementMonth);

    record MonthlyKey(UUID sellerId, YearMonth settlementMonth) {
    }

    record MonthlyAggregate(
            MonthlyKey key,
            long weeklySettlementCount,
            long aggregatedSettlementCount,
            long salesCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal refundAmount,
            BigDecimal payoutAmount) {
    }

    record MonthlyStatusCount(
            MonthlyKey key,
            SettlementDisplayStatus status,
            long count) {
    }

    record MonthlyPage(List<MonthlyAggregate> content, long totalElements) {
    }
}
