package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.repository.SettlementWeeklyQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementWeeklyQueryRepository.WeeklyPage;
import com.prompthub.admin.settlement.domain.repository.SettlementWeeklyStatusCount;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementWeeklyQueryRepositoryAdapter implements SettlementWeeklyQueryRepository {

    private final SettlementQueryJpaRepository jpaRepository;

    @Override
    public WeeklyPage findWeeklyPage(
            SettlementDisplayStatus status,
            YearMonth settlementMonth,
            int page,
            int size) {
        LocalDate periodStart = periodStart(settlementMonth);
        LocalDate periodEnd = periodEnd(settlementMonth);
        Page<Settlement> result =
                jpaRepository.findWeeklyPage(status, periodStart, periodEnd, PageRequest.of(page, size));
        return new WeeklyPage(result.getContent(), result.getTotalElements());
    }

    @Override
    public List<SettlementWeeklyStatusCount> findStatusCounts(YearMonth settlementMonth) {
        return jpaRepository.countWeeklyStatuses(
                periodStart(settlementMonth), periodEnd(settlementMonth));
    }

    private LocalDate periodStart(YearMonth settlementMonth) {
        return settlementMonth == null ? null : settlementMonth.atDay(1).minusDays(3);
    }

    private LocalDate periodEnd(YearMonth settlementMonth) {
        return settlementMonth == null ? null
                : settlementMonth.plusMonths(1).atDay(1).minusDays(3);
    }
}
