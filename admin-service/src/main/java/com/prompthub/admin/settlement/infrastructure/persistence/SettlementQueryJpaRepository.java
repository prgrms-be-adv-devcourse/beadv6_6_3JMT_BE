package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface SettlementQueryJpaRepository
	extends Repository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {
}
