package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.port.OutboxEventAppender;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementSourceLineJpaRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.cloud.config.fail-fast=false"
})
@ActiveProfiles("test")
class SettlementOutboxTransactionIntegrationTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21));

    @Autowired
    private SettlementCalculationApplicationService service;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementSourceLineJpaRepository sourceLineJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private OutboxEventAppender outboxEventAppender;

    @BeforeEach
    void setUp() {
        settlementJpaRepository.deleteAll();
        sourceLineJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("아웃박스 적재 실패 시 정산 생성과 source line 연결을 함께 롤백한다")
    void calculate_outboxAppendFails_rollsBackSettlementAndSourceLine() {
        // given
        UUID sellerId = UUID.randomUUID();
        SettlementSourceLine sourceLine = sourceLineJpaRepository.save(SettlementSourceLine.paid(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sellerId,
                new BigDecimal("10000.00"),
                LocalDateTime.of(2026, 6, 15, 10, 0)));
        CalculateSettlementCommand command = new CalculateSettlementCommand(
                UUID.randomUUID(), sellerId, PERIOD);
        willThrow(new IllegalStateException("outbox save failed"))
                .given(outboxEventAppender)
                .appendSettlementCreated(any(UUID.class), any());

        // when & then
        assertThatThrownBy(() -> service.calculate(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox save failed");

        entityManager.clear();
        assertThat(settlementJpaRepository.count()).isZero();
        SettlementSourceLine reloaded = sourceLineJpaRepository.findById(sourceLine.getId()).orElseThrow();
        assertThat(reloaded.isSettled()).isFalse();
        assertThat(reloaded.getSettlementId()).isNull();
    }
}
