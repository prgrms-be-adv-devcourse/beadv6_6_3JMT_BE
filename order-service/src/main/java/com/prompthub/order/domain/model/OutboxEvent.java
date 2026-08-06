package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.exception.OutboxEventInvalidStateException;
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
        ),
        @Index(
            name = "idx_order_outbox_event_publishable",
            columnList = "status, next_attempt_at, lease_until, occurred_at"
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

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    private OutboxEvent(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        int retryCount,
        LocalDateTime occurredAt,
        LocalDateTime publishedAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime lastAttemptAt,
        String lastError,
        String leaseOwner,
        LocalDateTime leaseUntil
    ) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.occurredAt = occurredAt;
        this.publishedAt = publishedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptAt = lastAttemptAt;
        this.lastError = lastError;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
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
            null,
            occurredAt,
            null,
            null,
            null,
            null
        );
    }

    public void claim(String owner, LocalDateTime until) {
        ensurePending("claim");
        this.leaseOwner = owner;
        this.leaseUntil = until;
    }

    public void markPublished(LocalDateTime publishedAt) {
        ensurePending("mark published");
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastAttemptAt = publishedAt;
        this.nextAttemptAt = null;
        clearLease();
    }

    public OutboxEventStatus recordPublishFailure(
        LocalDateTime attemptedAt,
        String error,
        OutboxRetryPolicy policy
    ) {
        ensurePending("record publish failure");
        this.retryCount++;
        this.lastAttemptAt = attemptedAt;
        this.lastError = error;
        clearLease();

        if (this.retryCount >= policy.maxAttempts()) {
            this.status = OutboxEventStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = policy.nextAttemptAt(attemptedAt, retryCount);
        }

        return status;
    }

    public void redrive(LocalDateTime dueAt) {
        if (status != OutboxEventStatus.FAILED) {
            throw new OutboxEventInvalidStateException(status, "redrive");
        }

        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = dueAt;
        clearLease();
    }

    public boolean isRetryAttempt() {
        return retryCount > 0;
    }

    private void ensurePending(String action) {
        if (status != OutboxEventStatus.PENDING) {
            throw new OutboxEventInvalidStateException(status, action);
        }
    }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseUntil = null;
    }
}
