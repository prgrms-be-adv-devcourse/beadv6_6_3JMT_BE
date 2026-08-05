package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import com.prompthub.settlement.domain.model.source.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregateRepository;
import com.prompthub.settlement.global.config.JpaAuditingConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, SettlementSourceAggregateRepositoryAdapter.class})
class SettlementSourceAggregateRepositoryIntegrationTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26));

    @Autowired
    private SettlementSourceLineJpaRepository sourceLineJpaRepository;

    @Autowired
    private SettlementSourceAggregateRepository repository;

    @Test
    @DisplayName("기간 내 모든 PAID·REFUND를 정산 연결 여부와 무관하게 집계하고 종료 경계는 제외한다")
    void aggregate_inPeriod_includesSettledLinesAndExcludesEndBoundary() {
        SettlementSourceLine settledPaid = paid(
                "1000.00",
                PERIOD.startInclusive());
        settledPaid.markSettled(UUID.randomUUID());
        sourceLineJpaRepository.save(settledPaid);
        sourceLineJpaRepository.save(paid(
                "2000.00",
                PERIOD.startInclusive().plusDays(1)));
        sourceLineJpaRepository.save(refunded(
                "500.00",
                PERIOD.endExclusive().minusSeconds(1)));
        sourceLineJpaRepository.save(paid(
                "9000.00",
                PERIOD.startInclusive().minusSeconds(1)));
        sourceLineJpaRepository.save(refunded(
                "9000.00",
                PERIOD.endExclusive()));
        sourceLineJpaRepository.flush();

        SettlementSourceAggregate result = repository.aggregate(PERIOD);

        assertThat(result.paidCount()).isEqualTo(2L);
        assertThat(result.paidAmount()).isEqualByComparingTo("3000.00");
        assertThat(result.refundCount()).isEqualTo(1L);
        assertThat(result.refundAmount()).isEqualByComparingTo("500.00");
    }

    private SettlementSourceLine paid(String amount, java.time.LocalDateTime occurredAt) {
        return SettlementSourceLine.paid(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal(amount),
                occurredAt);
    }

    private SettlementSourceLine refunded(String amount, java.time.LocalDateTime occurredAt) {
        return SettlementSourceLine.refunded(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal(amount),
                occurredAt);
    }
}
