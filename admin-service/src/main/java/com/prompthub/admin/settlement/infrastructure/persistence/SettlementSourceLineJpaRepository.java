package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.SettlementSourceLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface SettlementSourceLineJpaRepository extends Repository<SettlementSourceLine, UUID> {

	List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
