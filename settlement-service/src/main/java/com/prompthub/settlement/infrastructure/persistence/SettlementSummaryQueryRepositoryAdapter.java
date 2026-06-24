package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSummaryQueryRepositoryAdapter implements SettlementSummaryQueryRepository {

    private final SettlementSummaryJpaRepository jpaRepository;

    @Override
    public List<SettlementStatusAggregate> aggregateByStatus() {
        return jpaRepository.aggregateByStatus();
    }
}
