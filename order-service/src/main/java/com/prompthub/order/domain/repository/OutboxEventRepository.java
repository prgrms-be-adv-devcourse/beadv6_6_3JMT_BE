package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.model.OutboxRetryPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository {

	OutboxEvent save(OutboxEvent outboxEvent);

	Optional<OutboxEvent> claimNextPublishable(
		LocalDateTime now,
		String leaseOwner,
		LocalDateTime leaseUntil
	);

	boolean markPublished(UUID eventId, String leaseOwner, LocalDateTime attemptedAt);

	Optional<OutboxEventStatus> recordPublishFailure(
		UUID eventId,
		String leaseOwner,
		LocalDateTime attemptedAt,
		String lastError,
		OutboxRetryPolicy retryPolicy
	);

	Page<OutboxEvent> findFailed(Pageable pageable);

	Optional<OutboxEvent> findByIdForUpdate(UUID eventId);

	long countByStatus(OutboxEventStatus status);

	Optional<LocalDateTime> findOldestUnpublishedOccurredAt();
}
