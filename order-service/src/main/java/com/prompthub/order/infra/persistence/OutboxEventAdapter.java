package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventAdapter implements OutboxEventRepository {

	private final OutboxEventPersistence outboxEventPersistence;

	@Override
	public OutboxEvent save(OutboxEvent outboxEvent) {
		return outboxEventPersistence.save(outboxEvent);
	}
}
