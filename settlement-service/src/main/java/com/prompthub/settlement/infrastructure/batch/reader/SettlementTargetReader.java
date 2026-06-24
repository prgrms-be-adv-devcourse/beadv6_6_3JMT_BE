package com.prompthub.settlement.infrastructure.batch.reader;

import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.infrastructure.batch.model.SettlementItem;
import java.time.YearMonth;
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

    @Value("#{jobParameters['period']}")
    private String periodParam;

    @Value("#{jobExecutionContext['settlementBatchId']}")
    private String settlementBatchIdParam;

    private Iterator<UUID> sellerIdIterator;

    @Override
    public SettlementItem read() {
        YearMonth period = YearMonth.parse(periodParam);

        if (sellerIdIterator == null) {
            sellerIdIterator = settlementSourceRepository.findSettleableSellerIds(period).iterator();
        }

        if (!sellerIdIterator.hasNext()) {
            return null;
        }

        return new SettlementItem(sellerIdIterator.next(), period, UUID.fromString(settlementBatchIdParam));
    }
}
