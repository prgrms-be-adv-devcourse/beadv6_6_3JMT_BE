package com.prompthub.settlement.application.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import com.prompthub.settlement.application.port.OutboxEventAppender;
import com.prompthub.settlement.application.port.RequiresNewTransactionExecutor;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class OutboxEventApplicationServiceTest {

    private static final int PAGE_SIZE = 2;

    private OutboxEventRepository repository;
    private OutboxEventAppender appender;
    private SettlementEventPublisher publisher;
    private RequiresNewTransactionExecutor transactionExecutor;
    private OutboxEventApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        appender = mock(OutboxEventAppender.class);
        publisher = mock(SettlementEventPublisher.class);
        transactionExecutor = action -> action.run();
        service = new OutboxEventApplicationService(
                repository,
                appender,
                publisher,
                transactionExecutor,
                PAGE_SIZE,
                3);
    }

    @Test
    @DisplayName("정산 생성 이벤트 등록을 Outbox 저장 포트에 위임한다")
    void appendSettlementCreated_delegatesToAppender() {
        UUID batchId = UUID.randomUUID();
        SettlementCreatedEvent event = mock(SettlementCreatedEvent.class);

        service.appendSettlementCreated(batchId, event);

        then(appender).should().appendSettlementCreated(batchId, event);
    }

    @Test
    @DisplayName("시작 flush는 과거 PENDING 후보를 cursor로 여러 페이지 끝까지 발행한다")
    void flushPendingBefore_paginatesWithCursor() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 13, 10, 0);
        OutboxCandidate first = candidate(1, cutoff.minusMinutes(3));
        OutboxCandidate second = candidate(2, cutoff.minusMinutes(2));
        OutboxCandidate third = candidate(3, cutoff.minusMinutes(1));
        given(repository.findPendingBefore(
                eq(cutoff), nullable(LocalDateTime.class), nullable(UUID.class), eq(PAGE_SIZE)))
                .willReturn(List.of(first, second))
                .willReturn(List.of(third))
                .willReturn(List.of());
        stubEvent(first);
        stubEvent(second);
        stubEvent(third);

        service.flushPendingBefore(cutoff);

        InOrder inOrder = Mockito.inOrder(repository, publisher);
        inOrder.verify(repository).findPendingBefore(cutoff, null, null, PAGE_SIZE);
        verifyPublished(inOrder, first);
        verifyPublished(inOrder, second);
        inOrder.verify(repository).findPendingBefore(
                cutoff, second.occurredAt(), second.eventId(), PAGE_SIZE);
        verifyPublished(inOrder, third);
        inOrder.verify(repository).findPendingBefore(
                cutoff, third.occurredAt(), third.eventId(), PAGE_SIZE);
    }

    @Test
    @DisplayName("마지막 flush는 현재 배치 PENDING 후보만 순서대로 발행한다")
    void flushBatch_publishesCurrentBatchCandidates() {
        UUID batchId = UUID.randomUUID();
        OutboxCandidate first = candidate(11, LocalDateTime.of(2026, 7, 13, 11, 0));
        OutboxCandidate second = candidate(12, LocalDateTime.of(2026, 7, 13, 11, 1));
        given(repository.findPendingByBatchId(
                eq(batchId), nullable(LocalDateTime.class), nullable(UUID.class), eq(PAGE_SIZE)))
                .willReturn(List.of(first, second))
                .willReturn(List.of());
        stubEvent(first);
        stubEvent(second);

        service.flushBatch(batchId);

        InOrder inOrder = Mockito.inOrder(repository, publisher);
        inOrder.verify(repository).findPendingByBatchId(batchId, null, null, PAGE_SIZE);
        verifyPublished(inOrder, first);
        verifyPublished(inOrder, second);
        inOrder.verify(repository).findPendingByBatchId(
                batchId, second.occurredAt(), second.eventId(), PAGE_SIZE);
    }

    private void verifyPublished(InOrder inOrder, OutboxCandidate candidate) {
        inOrder.verify(repository).findById(candidate.eventId());
        inOrder.verify(publisher).publish(
                "settlement-events",
                candidate.eventId(),
                payload(candidate.eventId()));
    }

    private void stubEvent(OutboxCandidate candidate) {
        SettlementOutboxEvent event = SettlementOutboxEvent.create(
                candidate.eventId(),
                UUID.randomUUID(),
                "SETTLEMENT",
                candidate.eventId(),
                "SETTLEMENT_CREATED",
                "settlement-events",
                payload(candidate.eventId()),
                candidate.occurredAt());
        given(repository.findById(candidate.eventId())).willReturn(Optional.of(event));
    }

    private OutboxCandidate candidate(long suffix, LocalDateTime occurredAt) {
        return new OutboxCandidate(new UUID(0L, suffix), occurredAt);
    }

    private String payload(UUID eventId) {
        return "{\"eventId\":\"" + eventId + "\"}";
    }
}
