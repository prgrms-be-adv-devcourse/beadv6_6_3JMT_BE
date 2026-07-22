package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import java.util.List;

public interface OutboxEventRepository {

	OutboxEvent save(OutboxEvent outboxEvent);

	List<OutboxEvent> findPendingEvents(int batchSize);
}
