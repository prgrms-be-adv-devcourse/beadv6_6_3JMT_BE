package com.prompthub.notification.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "processed_event")
@IdClass(ProcessedEventId.class)
@NoArgsConstructor(access = PROTECTED)
public class ProcessedEvent {
    @Id
    @Column(name = "event_id", columnDefinition = "uuid")
    private UUID eventId;
    @Id
    @Column(name = "consumer_group", length = 100)
    private String consumerGroup;
    @Column(name = "notification_id", nullable = false, columnDefinition = "uuid")
    private UUID notificationId;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, String consumerGroup, UUID notificationId, Instant processedAt) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.notificationId = notificationId;
        this.processedAt = processedAt;
    }
}
