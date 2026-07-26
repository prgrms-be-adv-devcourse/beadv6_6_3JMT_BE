package com.prompthub.notification.infra.persistence;

import com.prompthub.notification.domain.model.ProcessedEvent;
import com.prompthub.notification.domain.model.ProcessedEventId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    Optional<ProcessedEvent> findByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
