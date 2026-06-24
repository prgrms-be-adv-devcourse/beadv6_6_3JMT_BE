package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OutboxEventAdapter implements OutboxEventRepository {

	private final OutboxEventPersistence outboxEventPersistence;

	@Override
	public OutboxEvent save(OutboxEvent outboxEvent) {
		return outboxEventPersistence.save(outboxEvent);
	}

	@Override
	public List<OutboxEvent> findPendingEvents(int batchSize) {
		return outboxEventPersistence.findByStatusOrderByOccurredAtAsc(
			OutboxEventStatus.PENDING,
			PageRequest.of(0, batchSize)
		);
	}
}
