package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.Settlement;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository {

	Optional<Settlement> findById(UUID id);

	Settlement save(Settlement settlement);
}
