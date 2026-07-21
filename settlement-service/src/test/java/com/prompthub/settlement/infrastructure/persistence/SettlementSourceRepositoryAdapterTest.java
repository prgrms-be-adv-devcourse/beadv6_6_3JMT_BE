package com.prompthub.settlement.infrastructure.persistence;

import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSourceRepositoryAdapterTest {

    @Mock
    private SettlementSourceLineJpaRepository jpaRepository;

    @InjectMocks
    private SettlementSourceRepositoryAdapter adapter;

    @Test
    void weeklyQueriesUseStartInclusiveAndNextMondayExclusive() {
        SettlementPeriod period = SettlementPeriod.of(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        UUID sellerId = UUID.randomUUID();

        adapter.findSettleableSellerIds(period);
        adapter.findSettleableLines(sellerId, period);

        LocalDateTime startInclusive = LocalDateTime.of(2026, 7, 13, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 7, 20, 0, 0);
        then(jpaRepository).should().findSettleableSellerIds(startInclusive, endExclusive);
        then(jpaRepository).should().findSettleableLines(sellerId, startInclusive, endExclusive);
    }
}
