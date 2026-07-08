package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OrderProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<OrderProcessedEvent, Long> {
    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
