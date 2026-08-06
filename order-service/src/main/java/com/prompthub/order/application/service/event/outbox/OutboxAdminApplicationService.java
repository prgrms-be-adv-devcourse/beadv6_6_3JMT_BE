package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RedriveOutcome;
import com.prompthub.order.application.usecase.OutboxAdminUseCase;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRedriveHistory;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.domain.repository.OutboxRedriveHistoryRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxAdminApplicationService implements OutboxAdminUseCase {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRedriveHistoryRepository outboxRedriveHistoryRepository;
    private final OutboxMetrics outboxMetrics;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Page<OutboxEventSummary> getFailedEvents(int page, int size) {
        return outboxEventRepository.findFailed(PageRequest.of(page - 1, size))
            .map(OutboxEventSummary::from);
    }

    @Override
    @Transactional
    public OutboxRedriveResult redrive(UUID eventId, UUID requestedBy, String reason) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> rejected(eventId, ErrorCode.OUTBOX_EVENT_NOT_FOUND));
        if (event.getStatus() != OutboxEventStatus.FAILED) {
            throw rejected(eventId, ErrorCode.OUTBOX_REDRIVE_NOT_ALLOWED);
        }

        LocalDateTime requestedAt = LocalDateTime.now(clock);
        OutboxRedriveHistory history = OutboxRedriveHistory.create(
            event, requestedBy, reason, requestedAt
        );
        event.redrive(requestedAt);
        outboxRedriveHistoryRepository.saveAndFlush(history);
        recordRedriveQuietly(RedriveOutcome.SUCCESS);
        log.info("Outbox redrive request processed. eventId={} outcome=success errorCode=none", eventId);
        return OutboxRedriveResult.from(event);
    }

    private OrderException rejected(UUID eventId, ErrorCode errorCode) {
        recordRedriveQuietly(RedriveOutcome.FAILURE);
        log.warn(
            "Outbox redrive request rejected. eventId={} outcome=failure errorCode={}",
            eventId,
            errorCode.getCode()
        );
        return new OrderException(errorCode);
    }

    private void recordRedriveQuietly(RedriveOutcome outcome) {
        try {
            outboxMetrics.recordRedrive(outcome);
        } catch (RuntimeException ignored) {
            log.warn("Outbox redrive metric recording failed. outcome={}", outcome);
        }
    }
}
