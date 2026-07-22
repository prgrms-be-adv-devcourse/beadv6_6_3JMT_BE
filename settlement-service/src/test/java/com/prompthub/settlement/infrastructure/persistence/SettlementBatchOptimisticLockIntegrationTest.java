package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.global.config.JpaAuditingConfig;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class SettlementBatchOptimisticLockIntegrationTest {

    @Autowired
    private SettlementBatchJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("같은 FAILED 배치에 대한 동시 재시작 상태 변경 중 하나만 저장된다")
    void save_staleRetryRequest_throwsOptimisticLockException() {
        SettlementBatch batch = SettlementBatch.start(
                "SETTLE-20260713-20260719-SCHEDULED-101",
                11L,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                TriggerType.SCHEDULED);
        batch.fail("첫 실행 실패");
        SettlementBatch saved = repository.saveAndFlush(batch);
        entityManager.clear();

        SettlementBatch first = repository.findById(saved.getId()).orElseThrow();
        entityManager.detach(first);
        SettlementBatch second = repository.findById(saved.getId()).orElseThrow();
        entityManager.detach(second);

        first.requestRetry();
        repository.saveAndFlush(first);
        entityManager.clear();

        second.requestRetry();
        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
