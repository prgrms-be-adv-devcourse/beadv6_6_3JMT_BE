package com.prompthub.settlement.infrastructure.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import com.prompthub.settlement.global.config.JpaAuditingConfig;
import com.prompthub.settlement.infrastructure.persistence.SettlementBatchJpaRepository;
import java.time.LocalDate;
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

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 7, 13, 10, 0);

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SettlementBatchJpaRepository settlementBatchJpaRepository;

    @Test
    @DisplayName("시작 flush는 이번 실행 전까지 미발행된 PENDING 후보만 발생 순서대로 조회한다")
    void findPendingBefore_filtersAttemptedAtAndStatus() {
        // given
        UUID batchId = completedBatch(101L).getId();
        SettlementOutboxEvent neverAttempted = event(
                "00000000-0000-0000-0000-000000000001", batchId, BASE_TIME);
        SettlementOutboxEvent attemptedBefore = event("00000000-0000-0000-0000-000000000002", batchId,
                BASE_TIME.plusMinutes(1));
        attemptedBefore.recordPublishFailure("old failure", BASE_TIME.plusMinutes(2), 3);
        SettlementOutboxEvent attemptedAfter = event("00000000-0000-0000-0000-000000000003", batchId,
                BASE_TIME.plusMinutes(2));
        attemptedAfter.recordPublishFailure("new failure", BASE_TIME.plusMinutes(6), 3);
        SettlementOutboxEvent published = event("00000000-0000-0000-0000-000000000004", batchId,
                BASE_TIME.plusMinutes(3));
        published.markPublished(BASE_TIME.plusMinutes(4));
        SettlementOutboxEvent failed = event("00000000-0000-0000-0000-000000000005", batchId,
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
        UUID batchA = completedBatch(111L).getId();
        UUID batchB = completedBatch(112L).getId();
        SettlementOutboxEvent first = event(
                "00000000-0000-0000-0000-000000000011", batchA, BASE_TIME);
        SettlementOutboxEvent second = event("00000000-0000-0000-0000-000000000012", batchA,
                BASE_TIME.plusMinutes(1));
        SettlementOutboxEvent third = event("00000000-0000-0000-0000-000000000013", batchA,
                BASE_TIME.plusMinutes(2));
        SettlementOutboxEvent otherBatch = event("00000000-0000-0000-0000-000000000014", batchB,
                BASE_TIME.plusMinutes(1));
        saveAll(first, second, third, otherBatch);

        // when
        List<OutboxCandidate> candidates = repository.findPendingByBatchId(
                batchA, first.getOccurredAt(), first.getEventId(), 1);

        // then
        assertThat(candidates)
                .extracting(OutboxCandidate::eventId)
                .containsExactly(second.getEventId());
    }

    @Test
    @DisplayName("일반 cursor 조회는 COMPLETED 배치의 PENDING 이벤트만 반환한다")
    void findPendingBeforeAfterCursor_returnsOnlyCompletedBatchEvents() {
        SettlementBatch completed = completedBatch(121L);
        SettlementBatch failed = failedBatch(122L);
        SettlementBatch retryRequested = failedBatch(123L);
        retryRequested.requestRetry();
        settlementBatchJpaRepository.saveAndFlush(retryRequested);
        SettlementBatch processing = processingBatch(124L);

        SettlementOutboxEvent completedEvent = event(
                "00000000-0000-0000-0000-000000000021",
                completed.getId(),
                BASE_TIME);
        SettlementOutboxEvent failedEvent = event(
                "00000000-0000-0000-0000-000000000022",
                failed.getId(),
                BASE_TIME.plusMinutes(1));
        SettlementOutboxEvent retryEvent = event(
                "00000000-0000-0000-0000-000000000023",
                retryRequested.getId(),
                BASE_TIME.plusMinutes(2));
        SettlementOutboxEvent processingEvent = event(
                "00000000-0000-0000-0000-000000000024",
                processing.getId(),
                BASE_TIME.plusMinutes(3));
        saveAll(completedEvent, failedEvent, retryEvent, processingEvent);

        List<OutboxCandidate> candidates = repository.findPendingBefore(
                BASE_TIME.plusHours(1),
                BASE_TIME.minusMinutes(1),
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                10);

        assertThat(candidates)
                .extracting(OutboxCandidate::eventId)
                .containsExactly(completedEvent.getEventId());
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

    private SettlementBatch completedBatch(long jobInstanceId) {
        SettlementBatch batch = processingBatch(jobInstanceId);
        batch.complete();
        return settlementBatchJpaRepository.saveAndFlush(batch);
    }

    private SettlementBatch failedBatch(long jobInstanceId) {
        SettlementBatch batch = processingBatch(jobInstanceId);
        batch.fail("정산 실패");
        return settlementBatchJpaRepository.saveAndFlush(batch);
    }

    private SettlementBatch processingBatch(long jobInstanceId) {
        return settlementBatchJpaRepository.saveAndFlush(SettlementBatch.start(
                "SETTLE-20260713-20260719-SCHEDULED-" + jobInstanceId,
                jobInstanceId,
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19),
                TriggerType.SCHEDULED));
    }
}
