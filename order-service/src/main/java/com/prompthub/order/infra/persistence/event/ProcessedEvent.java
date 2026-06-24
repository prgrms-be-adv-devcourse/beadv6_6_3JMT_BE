package com.prompthub.order.infra.persistence.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "processed_event",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_processed_event_event_group", columnNames = {"event_id", "consumer_group"})
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "event_id", columnDefinition = "CHAR(36)", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Builder
    public ProcessedEvent(String id, String eventId, String eventType, String consumerGroup, LocalDateTime processedAt) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.eventId = eventId;
        this.eventType = eventType;
        this.consumerGroup = consumerGroup;
        this.processedAt = processedAt != null ? processedAt : LocalDateTime.now();
    }
}
