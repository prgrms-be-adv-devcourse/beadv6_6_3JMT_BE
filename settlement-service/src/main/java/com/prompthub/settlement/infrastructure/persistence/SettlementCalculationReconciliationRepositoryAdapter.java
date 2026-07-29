package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import com.prompthub.settlement.domain.repository.SettlementCalculationReconciliationRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementCalculationReconciliationRepositoryAdapter
        implements SettlementCalculationReconciliationRepository {

    private final SettlementCalculationReconciliationJpaRepository jpaRepository;

    @Override
    public List<SettlementCalculationReconciliation> saveAll(
            List<SettlementCalculationReconciliation> reconciliations) {
        return jpaRepository.saveAll(reconciliations);
    }

    @Override
    public List<SettlementCalculationReconciliation>
            findBySettlementBatchIdOrderByVerifiedAtAsc(UUID settlementBatchId) {
        return jpaRepository.findBySettlementBatchIdOrderByVerifiedAtAsc(
                settlementBatchId);
    }
}
