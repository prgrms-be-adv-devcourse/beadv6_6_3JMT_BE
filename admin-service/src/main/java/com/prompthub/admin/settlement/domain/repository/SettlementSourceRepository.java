package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import java.util.List;
import java.util.UUID;

public interface SettlementSourceRepository {

	List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
