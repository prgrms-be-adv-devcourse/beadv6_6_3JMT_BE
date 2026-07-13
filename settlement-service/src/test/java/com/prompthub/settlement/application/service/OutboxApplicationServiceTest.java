package com.prompthub.settlement.application.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class OutboxApplicationServiceTest {

    private static final int PAGE_SIZE = 2;

    private OutboxEventRepository repository;
    private OutboxEventPublishService publishService;
    private OutboxApplicationService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OutboxEventRepository.class);
        publishService = Mockito.mock(OutboxEventPublishService.class);
        service = new OutboxApplicationService(repository, publishService, PAGE_SIZE);
    }

    @Test
    @DisplayName("시작 flush는 과거 PENDING 후보를 cursor로 여러 페이지 끝까지 발행한다")
    void flushPendingBefore_paginatesWithCursor() {
        // given
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 13, 10, 0);
        OutboxCandidate first = candidate(1, cutoff.minusMinutes(3));
        OutboxCandidate second = candidate(2, cutoff.minusMinutes(2));
        OutboxCandidate third = candidate(3, cutoff.minusMinutes(1));
        given(repository.findPendingBefore(
                eq(cutoff), nullable(LocalDateTime.class), nullable(UUID.class), eq(PAGE_SIZE)))
                .willReturn(List.of(first, second))
                .willReturn(List.of(third))
                .willReturn(List.of());

        // when
        service.flushPendingBefore(cutoff);

        // then
        InOrder inOrder = Mockito.inOrder(repository, publishService);
        inOrder.verify(repository).findPendingBefore(cutoff, null, null, PAGE_SIZE);
        inOrder.verify(publishService).publish(first.eventId());
        inOrder.verify(publishService).publish(second.eventId());
        inOrder.verify(repository).findPendingBefore(
                cutoff, second.occurredAt(), second.eventId(), PAGE_SIZE);
        inOrder.verify(publishService).publish(third.eventId());
        inOrder.verify(repository).findPendingBefore(
                cutoff, third.occurredAt(), third.eventId(), PAGE_SIZE);
    }

    @Test
    @DisplayName("마지막 flush는 현재 배치 PENDING 후보만 순서대로 발행한다")
    void flushBatch_publishesCurrentBatchCandidates() {
        // given
        UUID batchId = UUID.randomUUID();
        OutboxCandidate first = candidate(11, LocalDateTime.of(2026, 7, 13, 11, 0));
        OutboxCandidate second = candidate(12, LocalDateTime.of(2026, 7, 13, 11, 1));
        given(repository.findPendingByBatchId(
                eq(batchId), nullable(LocalDateTime.class), nullable(UUID.class), eq(PAGE_SIZE)))
                .willReturn(List.of(first, second))
                .willReturn(List.of());

        // when
        service.flushBatch(batchId);

        // then
        InOrder inOrder = Mockito.inOrder(repository, publishService);
        inOrder.verify(repository).findPendingByBatchId(batchId, null, null, PAGE_SIZE);
        inOrder.verify(publishService).publish(first.eventId());
        inOrder.verify(publishService).publish(second.eventId());
        inOrder.verify(repository).findPendingByBatchId(
                batchId, second.occurredAt(), second.eventId(), PAGE_SIZE);
    }

    @Test
    @DisplayName("redrive는 지정된 이벤트 한 건만 재처리 서비스에 위임한다")
    void redrive_delegatesTargetEvent() {
        // given
        UUID eventId = UUID.randomUUID();

        // when
        service.redrive(eventId);

        // then
        then(publishService).should().redrive(eventId);
        then(repository).shouldHaveNoInteractions();
    }

    private OutboxCandidate candidate(long suffix, LocalDateTime occurredAt) {
        return new OutboxCandidate(new UUID(0L, suffix), occurredAt);
    }
}
