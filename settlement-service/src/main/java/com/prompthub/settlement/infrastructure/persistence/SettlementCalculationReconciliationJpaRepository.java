package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.calculation.SettlementCalculationReconciliation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementCalculationReconciliationJpaRepository
        extends JpaRepository<SettlementCalculationReconciliation, UUID> {

    List<SettlementCalculationReconciliation>
            findBySettlementBatchIdOrderByVerifiedAtAsc(UUID settlementBatchId);
}
