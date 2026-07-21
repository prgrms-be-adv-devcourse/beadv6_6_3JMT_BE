package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSourceRepositoryAdapter implements SettlementSourceRepository {

    private final SettlementSourceLineJpaRepository jpaRepository;

    @Override
    public void saveAll(List<SettlementSourceLine> lines) {
        jpaRepository.saveAll(lines);
    }

    @Override
    public List<UUID> findExistingEventIds(Collection<UUID> eventIds) {
        return jpaRepository.findExistingEventIds(eventIds);
    }

    @Override
    public List<UUID> findSettleableSellerIds(SettlementPeriod period) {
        return jpaRepository.findSettleableSellerIds(period.startInclusive(), period.endExclusive());
    }

    @Override
    public List<SettlementSourceLine> findSettleableLines(UUID sellerId, SettlementPeriod period) {
        return jpaRepository.findSettleableLines(sellerId, period.startInclusive(), period.endExclusive());
    }

    @Override
    public List<SettlementSourceLine> findBySettlementId(UUID settlementId) {
        return jpaRepository.findBySettlementId(settlementId);
    }
}
