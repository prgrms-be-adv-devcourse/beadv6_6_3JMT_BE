package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import java.util.List;

public interface SettlementQueryRepository {

	SettlementPage findPage(SettlementDisplayStatus status, int page, int size);

	List<SettlementStatusAggregate> aggregateByStatus();

	record SettlementPage(List<Settlement> content, long totalElements) {
	}
}
