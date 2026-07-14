package com.prompthub.settlement.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import com.prompthub.settlement.global.config.JpaAuditingConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, OutboxEventRepositoryAdapter.class})
class OutboxEventRepositoryAdapterTest {

    private static final UUID BATCH_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_B = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 7, 13, 10, 0);

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("시작 flush는 이번 실행 전까지 미발행된 PENDING 후보만 발생 순서대로 조회한다")
    void findPendingBefore_filtersAttemptedAtAndStatus() {
        // given
        SettlementOutboxEvent neverAttempted = event(
                "00000000-0000-0000-0000-000000000001", BATCH_A, BASE_TIME);
        SettlementOutboxEvent attemptedBefore = event("00000000-0000-0000-0000-000000000002", BATCH_A,
                BASE_TIME.plusMinutes(1));
        attemptedBefore.recordPublishFailure("old failure", BASE_TIME.plusMinutes(2), 3);
        SettlementOutboxEvent attemptedAfter = event("00000000-0000-0000-0000-000000000003", BATCH_A,
                BASE_TIME.plusMinutes(2));
        attemptedAfter.recordPublishFailure("new failure", BASE_TIME.plusMinutes(6), 3);
        SettlementOutboxEvent published = event("00000000-0000-0000-0000-000000000004", BATCH_A,
                BASE_TIME.plusMinutes(3));
        published.markPublished(BASE_TIME.plusMinutes(4));
        SettlementOutboxEvent failed = event("00000000-0000-0000-0000-000000000005", BATCH_A,
                BASE_TIME.plusMinutes(4));
        failed.recordPublishFailure("terminal failure", BASE_TIME.plusMinutes(4), 1);
        saveAll(neverAttempted, attemptedBefore, attemptedAfter, published, failed);

        // when
        List<OutboxCandidate> candidates = repository.findPendingBefore(
                BASE_TIME.plusMinutes(5), null, null, 10);

        // then
        assertThat(candidates)
                .extracting(OutboxCandidate::eventId)
                .containsExactly(neverAttempted.getEventId(), attemptedBefore.getEventId());
    }

    @Test
    @DisplayName("현재 배치 flush는 해당 배치의 PENDING 이벤트를 cursor 다음부터 제한 개수만큼 조회한다")
    void findPendingByBatchId_usesStableCursorAndLimit() {
        // given
        SettlementOutboxEvent first = event(
                "00000000-0000-0000-0000-000000000011", BATCH_A, BASE_TIME);
        SettlementOutboxEvent second = event("00000000-0000-0000-0000-000000000012", BATCH_A,
                BASE_TIME.plusMinutes(1));
        SettlementOutboxEvent third = event("00000000-0000-0000-0000-000000000013", BATCH_A,
                BASE_TIME.plusMinutes(2));
        SettlementOutboxEvent otherBatch = event("00000000-0000-0000-0000-000000000014", BATCH_B,
                BASE_TIME.plusMinutes(1));
        saveAll(first, second, third, otherBatch);

        // when
        List<OutboxCandidate> candidates = repository.findPendingByBatchId(
                BATCH_A, first.getOccurredAt(), first.getEventId(), 1);

        // then
        assertThat(candidates)
                .extracting(OutboxCandidate::eventId)
                .containsExactly(second.getEventId());
    }

    private SettlementOutboxEvent event(String eventId, UUID batchId, LocalDateTime occurredAt) {
        UUID id = UUID.fromString(eventId);
        return SettlementOutboxEvent.create(
                id,
                batchId,
                "SETTLEMENT",
                UUID.randomUUID(),
                "SETTLEMENT_CREATED",
                "settlement-events",
                "{\"eventId\":\"" + id + "\"}",
                occurredAt);
    }

    private void saveAll(SettlementOutboxEvent... events) {
        for (SettlementOutboxEvent event : events) {
            repository.save(event);
        }
        entityManager.flush();
        entityManager.clear();
    }
}
