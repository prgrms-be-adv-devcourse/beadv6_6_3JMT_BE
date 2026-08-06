package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.service.event.outbox.OutboxMetrics.RedriveOutcome;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxRedriveApplicationService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMetrics outboxMetrics;
    private final Clock clock;

    @Transactional
    public void redrive(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> missing(eventId));

        try {
            event.redrive(LocalDateTime.now(clock));
            recordRedriveQuietly(RedriveOutcome.SUCCESS);
        } catch (RuntimeException exception) {
            recordRedriveQuietly(RedriveOutcome.FAILURE);
            throw exception;
        }
    }

    private OrderException missing(UUID eventId) {
        recordRedriveQuietly(RedriveOutcome.FAILURE);
        return new OrderException(ErrorCode.OUTBOX_EVENT_NOT_FOUND);
    }

    private void recordRedriveQuietly(RedriveOutcome outcome) {
        try {
            outboxMetrics.recordRedrive(outcome);
        } catch (RuntimeException ignored) {
            // 지표 기록 실패가 redrive 상태 전이를 중단시키지 않도록 한다.
        }
    }
}
