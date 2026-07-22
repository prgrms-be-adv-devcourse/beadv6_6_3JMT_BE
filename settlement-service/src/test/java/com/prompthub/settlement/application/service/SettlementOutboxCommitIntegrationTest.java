package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementSourceLineJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.outbox.OutboxEventJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.cloud.config.fail-fast=false"
})
@ActiveProfiles("test")
class SettlementOutboxCommitIntegrationTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21));

    @Autowired
    private CalculateSettlementApplicationService service;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementSourceLineJpaRepository sourceLineJpaRepository;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outboxEventJpaRepository.deleteAll();
        settlementJpaRepository.deleteAll();
        sourceLineJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("정산·source line 연결·동일 eventId JSON 아웃박스를 한 트랜잭션으로 커밋한다")
    void calculate_commitsSettlementSourceAndStableOutbox() throws Exception {
        // given
        UUID sellerId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        SettlementSourceLine sourceLine = sourceLineJpaRepository.save(SettlementSourceLine.paid(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sellerId,
                new BigDecimal("10000.00"),
                LocalDateTime.of(2026, 6, 15, 10, 0)));

        // when
        Settlement settlement = service.calculate(
                new CalculateSettlementCommand(batchId, sellerId, PERIOD));

        // then
        List<SettlementOutboxEvent> outboxEvents = outboxEventJpaRepository.findAll();
        assertThat(settlementJpaRepository.findById(settlement.getId())).isPresent();
        assertThat(sourceLineJpaRepository.findById(sourceLine.getId()).orElseThrow().getSettlementId())
                .isEqualTo(settlement.getId());
        assertThat(outboxEvents).hasSize(1);

        SettlementOutboxEvent outbox = outboxEvents.getFirst();
        JsonNode envelope = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getSettlementBatchId()).isEqualTo(batchId);
        assertThat(outbox.getAggregateId()).isEqualTo(settlement.getId());
        assertThat(envelope.path("eventId").stringValue()).isEqualTo(outbox.getEventId().toString());
        assertThat(envelope.path("aggregateId").stringValue()).isEqualTo(settlement.getId().toString());
    }
}
