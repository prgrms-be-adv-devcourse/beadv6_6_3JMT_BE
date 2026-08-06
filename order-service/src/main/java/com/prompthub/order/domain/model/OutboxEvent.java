package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
    name = "order_outbox_event",
    indexes = {
        @Index(
            name = "idx_order_outbox_event_status_occurred_at",
            columnList = "status, occurred_at"
        ),
        @Index(
            name = "idx_order_outbox_event_aggregate_id",
            columnList = "aggregate_id"
        )
    }
)
@NoArgsConstructor(access = PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    private OutboxEvent(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        int retryCount,
        LocalDateTime occurredAt,
        LocalDateTime publishedAt
    ) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.occurredAt = occurredAt;
        this.publishedAt = publishedAt;
    }

    public static OutboxEvent create(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        String payload,
        LocalDateTime occurredAt
    ) {
        return new OutboxEvent(
            eventId,
            aggregateId,
            eventType,
            payload,
            OutboxEventStatus.PENDING,
            0,
            occurredAt,
            null
        );
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    public void recordPublishFailure(int maxRetryCount) {
        this.retryCount++;

        if (this.retryCount >= maxRetryCount) {
            this.status = OutboxEventStatus.FAILED;
        }
    }
}
