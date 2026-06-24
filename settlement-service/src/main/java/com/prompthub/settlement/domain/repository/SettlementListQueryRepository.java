package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.util.List;

public interface SettlementListQueryRepository {

    SettlementPage findPage(SettlementDisplayStatus status, int page, int size);

    record SettlementPage(List<Settlement> content, long totalElements) {
    }
}
