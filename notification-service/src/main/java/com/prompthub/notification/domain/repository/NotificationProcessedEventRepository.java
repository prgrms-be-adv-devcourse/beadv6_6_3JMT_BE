package com.prompthub.notification.domain.repository;

import com.prompthub.notification.domain.model.NotificationProcessedEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface NotificationProcessedEventRepository extends JpaRepository<NotificationProcessedEvent, UUID> {
    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);

    @Modifying
    @Query(value = """
        INSERT INTO notification_processed_event (
            processed_event_id, event_id, consumer_group, event_type, processed_at
        ) VALUES (
            :processedEventId, :eventId, :consumerGroup, :eventType, :processedAt
        ) ON CONFLICT (event_id, consumer_group) DO NOTHING
        """, nativeQuery = true)
    int claim(
        @Param("processedEventId") UUID processedEventId,
        @Param("eventId") UUID eventId,
        @Param("consumerGroup") String consumerGroup,
        @Param("eventType") String eventType,
        @Param("processedAt") Instant processedAt
    );
}
