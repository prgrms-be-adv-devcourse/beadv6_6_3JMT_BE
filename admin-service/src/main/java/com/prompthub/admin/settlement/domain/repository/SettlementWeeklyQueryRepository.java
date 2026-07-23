package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import java.time.YearMonth;
import java.util.List;

public interface SettlementWeeklyQueryRepository {

    WeeklyPage findWeeklyPage(
            SettlementDisplayStatus status,
            YearMonth settlementMonth,
            int page,
            int size);

    List<SettlementWeeklyStatusCount> findStatusCounts(YearMonth settlementMonth);

    record WeeklyPage(List<Settlement> content, long totalElements) {
    }
}
