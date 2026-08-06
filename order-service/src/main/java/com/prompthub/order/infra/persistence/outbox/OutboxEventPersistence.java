package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventPersistence extends JpaRepository<OutboxEvent, UUID> {

	@Query(value = """
		select *
		from order_outbox_event
		where status = 'PENDING'
		  and (next_attempt_at is null or next_attempt_at <= :now)
		  and (lease_until is null or lease_until <= :now)
		order by occurred_at asc
		limit 1
		for update skip locked
		""", nativeQuery = true)
	Optional<OutboxEvent> findNextPublishableForUpdate(@Param("now") LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select event
		from OutboxEvent event
		where event.eventId = :eventId
		  and event.leaseOwner = :leaseOwner
		""")
	Optional<OutboxEvent> findClaimedByOwnerForUpdate(
		@Param("eventId") UUID eventId,
		@Param("leaseOwner") String leaseOwner
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select event from OutboxEvent event where event.eventId = :eventId")
	Optional<OutboxEvent> findByIdForUpdate(@Param("eventId") UUID eventId);

	long countByStatus(OutboxEventStatus status);

	@Query("""
		select min(event.occurredAt)
		from OutboxEvent event
		where event.status <> com.prompthub.order.domain.enums.OutboxEventStatus.PUBLISHED
		""")
	Optional<LocalDateTime> findOldestUnpublishedOccurredAt();
}
