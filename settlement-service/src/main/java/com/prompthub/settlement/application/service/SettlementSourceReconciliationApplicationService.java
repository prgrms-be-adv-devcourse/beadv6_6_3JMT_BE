package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.port.OrderSettlementQuery;
import com.prompthub.settlement.application.usecase.ReconcileSettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregateRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementSourceReconciliationApplicationService
        implements ReconcileSettlementSourceUseCase {

    private final OrderSettlementQuery orderSettlementQuery;
    private final SettlementSourceAggregateRepository settlementSourceAggregateRepository;

    @Override
    @Transactional(readOnly = true)
    public SettlementSourceReconciliationResult reconcile(SettlementPeriod period) {
        SettlementSourceAggregate orderAggregate =
                aggregate(orderSettlementQuery.fetchSettleableLines(period));
        SettlementSourceAggregate sourceAggregate =
                settlementSourceAggregateRepository.aggregate(period);
        return SettlementSourceReconciliationResult.compare(orderAggregate, sourceAggregate);
    }

    private SettlementSourceAggregate aggregate(List<SettleableLine> lines) {
        long paidCount = 0;
        BigDecimal paidAmount = BigDecimal.ZERO;
        long refundCount = 0;
        BigDecimal refundAmount = BigDecimal.ZERO;
        for (SettleableLine line : lines) {
            switch (line.lineType()) {
                case PAID -> {
                    paidCount++;
                    paidAmount = paidAmount.add(line.lineAmount());
                }
                case REFUND -> {
                    refundCount++;
                    refundAmount = refundAmount.add(line.lineAmount());
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
