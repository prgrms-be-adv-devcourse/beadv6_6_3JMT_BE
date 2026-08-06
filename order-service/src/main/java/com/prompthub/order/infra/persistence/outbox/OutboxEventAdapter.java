package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventAdapter implements OutboxEventRepository {

	private final OutboxEventPersistence outboxEventPersistence;

	@Override
	public OutboxEvent save(OutboxEvent outboxEvent) {
		return outboxEventPersistence.save(outboxEvent);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<OutboxEvent> claimNextPublishable(
		LocalDateTime now,
		String leaseOwner,
		LocalDateTime leaseUntil
	) {
		return outboxEventPersistence.findNextPublishableForUpdate(now)
			.map(event -> {
				event.claim(leaseOwner, leaseUntil);
				return event;
			});
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markPublished(UUID eventId, String leaseOwner, LocalDateTime attemptedAt) {
		return outboxEventPersistence.findClaimedByOwnerForUpdate(eventId, leaseOwner)
			.map(event -> {
				event.markPublished(attemptedAt);
				return true;
			})
			.orElse(false);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<OutboxEventStatus> recordPublishFailure(
		UUID eventId,
		String leaseOwner,
		LocalDateTime attemptedAt,
		String lastError,
		OutboxRetryPolicy retryPolicy
	) {
		return outboxEventPersistence.findClaimedByOwnerForUpdate(eventId, leaseOwner)
			.map(event -> event.recordPublishFailure(attemptedAt, lastError, retryPolicy));
	}

	@Override
	@Transactional
	public Optional<OutboxEvent> findByIdForUpdate(UUID eventId) {
		return outboxEventPersistence.findByIdForUpdate(eventId);
	}

	@Override
	public long countByStatus(OutboxEventStatus status) {
		return outboxEventPersistence.countByStatus(status);
	}

	@Override
	public Optional<LocalDateTime> findOldestUnpublishedOccurredAt() {
		return outboxEventPersistence.findOldestUnpublishedOccurredAt();
	}
}
