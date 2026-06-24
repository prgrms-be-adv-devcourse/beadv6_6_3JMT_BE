package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface SettlementQueryRepository {

    SettlementPage findPage(SettlementDisplayStatus status, int page, int size);

    SettlementPage findPageBySeller(UUID sellerId, SettlementDisplayStatus status, YearMonth period, int page, int size);

    List<SettlementStatusAggregate> aggregateByStatus();

    record SettlementPage(List<Settlement> content, long totalElements) {
    }
}
