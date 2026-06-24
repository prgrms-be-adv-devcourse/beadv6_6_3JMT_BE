package com.prompthub.order.infra.persistence.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);
}
