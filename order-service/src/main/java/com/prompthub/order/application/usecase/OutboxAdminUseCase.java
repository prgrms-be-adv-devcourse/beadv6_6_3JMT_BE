package com.prompthub.order.application.usecase;

import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OutboxAdminUseCase {

    Page<OutboxEventSummary> getFailedEvents(int page, int size);

    OutboxRedriveResult redrive(UUID eventId, UUID requestedBy, String reason);
}
