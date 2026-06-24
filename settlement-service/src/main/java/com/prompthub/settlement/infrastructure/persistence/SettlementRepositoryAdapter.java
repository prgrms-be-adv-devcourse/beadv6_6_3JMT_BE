package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementRepositoryAdapter implements SettlementRepository {

    private final SettlementJpaRepository jpaRepository;

    @Override
    public Settlement save(Settlement settlement) {
        return jpaRepository.save(settlement);
    }

    @Override
    public List<Settlement> saveAll(List<Settlement> settlements) {
        return jpaRepository.saveAll(settlements);
    }

    @Override
    public List<Settlement> findBySettlementBatchId(UUID settlementBatchId) {
        return jpaRepository.findBySettlementBatchId(settlementBatchId);
    }

    @Override
    public Optional<Settlement> findById(UUID id) {
        return jpaRepository.findById(id);
    }
}
