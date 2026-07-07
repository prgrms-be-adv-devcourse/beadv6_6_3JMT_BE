package com.prompthub.admin.settlement.infrastructure.persistence;

import com.prompthub.admin.settlement.domain.model.Settlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SettlementQueryJpaRepository
	extends JpaRepository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {
}
