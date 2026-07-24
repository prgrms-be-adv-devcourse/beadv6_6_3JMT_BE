package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.SettlementSourceLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface SettlementSourceLineJpaRepository extends Repository<SettlementSourceLine, UUID> {

	List<SettlementSourceLine> findBySettlementId(UUID settlementId);
}
