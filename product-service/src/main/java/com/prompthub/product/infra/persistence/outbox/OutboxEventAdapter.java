package com.prompthub.product.infra.persistence.outbox;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

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
		return outboxEventPersistence.findByStatusOrderByOccurredAtAsc(OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));
	}
}
