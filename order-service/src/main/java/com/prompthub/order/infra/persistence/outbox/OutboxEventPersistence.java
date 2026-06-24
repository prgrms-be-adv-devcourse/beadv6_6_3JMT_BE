package com.prompthub.order.infra.persistence.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventPersistence extends JpaRepository<OutboxEvent, UUID> {

	List<OutboxEvent> findByStatusOrderByOccurredAtAsc(
		OutboxEventStatus status,
		Pageable pageable
	);
}
