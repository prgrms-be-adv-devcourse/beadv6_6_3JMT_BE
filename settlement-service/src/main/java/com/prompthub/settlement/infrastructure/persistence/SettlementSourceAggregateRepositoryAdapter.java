package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregateRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSourceAggregateRepositoryAdapter
        implements SettlementSourceAggregateRepository {

    private final SettlementSourceLineJpaRepository jpaRepository;

    @Override
    public SettlementSourceAggregate aggregate(SettlementPeriod period) {
        long paidCount = 0;
        BigDecimal paidAmount = BigDecimal.ZERO;
        long refundCount = 0;
        BigDecimal refundAmount = BigDecimal.ZERO;
        for (SettlementSourceLineTypeAggregate row : jpaRepository.aggregateByPeriod(
                period.startInclusive(),
                period.endExclusive())) {
            switch (row.lineType()) {
                case PAID -> {
                    paidCount = row.lineCount();
                    paidAmount = row.totalAmount();
                }
                case REFUND -> {
                    refundCount = row.lineCount();
                    refundAmount = row.totalAmount();
                }
            }
        }
        return new SettlementSourceAggregate(
                paidCount,
                paidAmount,
                refundCount,
                refundAmount);
    }
}
