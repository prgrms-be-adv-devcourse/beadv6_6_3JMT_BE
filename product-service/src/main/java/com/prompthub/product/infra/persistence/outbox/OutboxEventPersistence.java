package com.prompthub.product.infra.persistence.outbox;

import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.model.enums.OutboxEventStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventPersistence extends JpaRepository<OutboxEvent, UUID> {

	List<OutboxEvent> findByStatusOrderByOccurredAtAsc(OutboxEventStatus status, Pageable pageable);
}
