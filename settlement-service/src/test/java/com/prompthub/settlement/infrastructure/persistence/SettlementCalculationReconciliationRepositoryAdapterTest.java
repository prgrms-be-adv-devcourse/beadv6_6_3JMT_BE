package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import com.prompthub.settlement.domain.model.SettlementCalculationSummary;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.repository.SettlementCalculationReconciliationRepository;
import com.prompthub.settlement.global.config.JpaAuditingConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaAuditingConfig.class,
    SettlementCalculationReconciliationRepositoryAdapter.class
})
class SettlementCalculationReconciliationRepositoryAdapterTest {

    @Autowired
    private SettlementCalculationReconciliationRepository repository;

    @Test
    @DisplayName("동일 정산의 재검증 결과를 검증 시각 순으로 모두 누적한다")
    void saveAll_sameSettlement_keepsEveryAttempt() {
        UUID batchId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        UUID sourceLineId = UUID.randomUUID();
        LocalDateTime firstVerifiedAt = LocalDateTime.of(2026, 7, 28, 10, 0);
        LocalDateTime secondVerifiedAt = firstVerifiedAt.plusMinutes(30);
        SettlementDetail detail = SettlementDetail.sale(
                sourceLineId,
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                new BigDecimal("0.15"),
                firstVerifiedAt.minusDays(1));
        SettlementCalculationSummary matched =
                new SettlementCalculationSummary(
                        1,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("15.00"),
                        new BigDecimal("85.00"));
        SettlementCalculationSummary mismatched =
                new SettlementCalculationSummary(
                        2,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("15.00"),
                        new BigDecimal("85.00"));

        repository.saveAll(List.of(
                SettlementCalculationReconciliation.verify(
                        batchId,
                        settlementId,
                        mismatched,
                        List.of(detail),
                        List.of(sourceLineId),
                        firstVerifiedAt),
                SettlementCalculationReconciliation.verify(
                        batchId,
                        settlementId,
                        matched,
                        List.of(detail),
                        List.of(sourceLineId),
                        secondVerifiedAt)));

        assertThat(repository.findBySettlementBatchIdOrderByVerifiedAtAsc(batchId))
                .hasSize(2)
                .extracting(SettlementCalculationReconciliation::getVerifiedAt)
                .containsExactly(firstVerifiedAt, secondVerifiedAt);
    }
}
