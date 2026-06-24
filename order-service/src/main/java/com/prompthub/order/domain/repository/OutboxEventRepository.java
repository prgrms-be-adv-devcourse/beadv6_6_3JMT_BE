package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OutboxEvent;

public interface OutboxEventRepository {

	OutboxEvent save(OutboxEvent outboxEvent);
}
