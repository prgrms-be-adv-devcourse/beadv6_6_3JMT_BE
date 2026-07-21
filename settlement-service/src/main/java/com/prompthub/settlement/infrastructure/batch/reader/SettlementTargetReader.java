package com.prompthub.settlement.infrastructure.batch.reader;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.infrastructure.batch.model.SettlementItem;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@StepScope
@RequiredArgsConstructor
public class SettlementTargetReader implements ItemReader<SettlementItem> {

    private final SettlementSourceRepository settlementSourceRepository;

    @Value("#{jobParameters['periodStart']}")
    private String periodStartParam;

    @Value("#{jobParameters['periodEnd']}")
    private String periodEndParam;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    private Iterator<UUID> sellerIdIterator;

    @Override
    public SettlementItem read() {
        SettlementPeriod period = period();

        if (sellerIdIterator == null) {
            sellerIdIterator = settlementSourceRepository.findSettleableSellerIds(period).iterator();
        }

        if (!sellerIdIterator.hasNext()) {
            return null;
        }

        return new SettlementItem(sellerIdIterator.next(), period, UUID.fromString(settlementBatchIdParam));
    }

    private SettlementPeriod period() {
        return SettlementPeriod.of(LocalDate.parse(periodStartParam), LocalDate.parse(periodEndParam));
    }
}
