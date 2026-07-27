package com.prompthub.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "notification_processed_event", uniqueConstraints = @UniqueConstraint(
    name = "uk_notification_processed_event_id_group", columnNames = {"event_id", "consumer_group"}
))
@NoArgsConstructor(access = PROTECTED)
public class NotificationProcessedEvent {

    @Id
    @Column(name = "processed_event_id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "event_id", columnDefinition = "uuid", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", length = 100, nullable = false)
    private String consumerGroup;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    private NotificationProcessedEvent(UUID eventId, String consumerGroup, String eventType) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public static NotificationProcessedEvent create(UUID eventId, String consumerGroup, String eventType) {
        return new NotificationProcessedEvent(eventId, consumerGroup, eventType);
    }
}
