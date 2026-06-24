package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OutboxEvent;

import java.util.List;

public interface OutboxEventRepository {

	OutboxEvent save(OutboxEvent outboxEvent);

	List<OutboxEvent> findPendingEvents(int batchSize);
}
