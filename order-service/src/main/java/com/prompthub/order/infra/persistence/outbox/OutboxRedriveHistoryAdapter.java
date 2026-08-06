package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.model.OutboxRedriveHistory;
import com.prompthub.order.domain.repository.OutboxRedriveHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRedriveHistoryAdapter implements OutboxRedriveHistoryRepository {

    private final OutboxRedriveHistoryPersistence outboxRedriveHistoryPersistence;

    @Override
    public OutboxRedriveHistory saveAndFlush(OutboxRedriveHistory history) {
        return outboxRedriveHistoryPersistence.saveAndFlush(history);
    }
}
