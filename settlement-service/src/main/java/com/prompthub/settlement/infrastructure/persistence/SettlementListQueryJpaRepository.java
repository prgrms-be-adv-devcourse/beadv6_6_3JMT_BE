package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

public interface SettlementListQueryJpaRepository
        extends Repository<Settlement, UUID>, JpaSpecificationExecutor<Settlement> {
}
