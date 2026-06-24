package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.repository.SettlementBatchRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementBatchRepositoryAdapter implements SettlementBatchRepository {

    private final SettlementBatchJpaRepository jpaRepository;

    @Override
    public SettlementBatch save(SettlementBatch settlementBatch) {
        return jpaRepository.save(settlementBatch);
    }

    @Override
    public Optional<SettlementBatch> findById(UUID batchId) {
        return jpaRepository.findById(batchId);
    }
}
