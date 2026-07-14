package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.domain.exception.OutboxEventInvalidStateException;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "settlement_outbox_event",
        indexes = {
            @Index(
                    name = "idx_settlement_outbox_status_attempted_occurred",
                    columnList = "status, last_attempted_at, occurred_at, event_id"),
            @Index(
                    name = "idx_settlement_outbox_batch_status_occurred",
                    columnList = "settlement_batch_id, status, occurred_at, event_id"),
            @Index(
                    name = "idx_settlement_outbox_aggregate_id",
                    columnList = "aggregate_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

    private static final int FAILURE_REASON_MAX_LENGTH = 1_000;

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "settlement_batch_id", nullable = false, updatable = false)
    private UUID settlementBatchId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false, length = 255)
    private String topic;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @Column(name = "last_failure_reason", length = FAILURE_REASON_MAX_LENGTH)
    private String lastFailureReason;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    private OutboxEvent(
            UUID eventId,
            UUID settlementBatchId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String topic,
            String payload,
            LocalDateTime occurredAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId는 필수입니다.");
        this.settlementBatchId = Objects.requireNonNull(settlementBatchId, "settlementBatchId는 필수입니다.");
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId는 필수입니다.");
        this.eventType = requireText(eventType, "eventType");
        this.topic = requireText(topic, "topic");
        this.payload = requireText(payload, "payload");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
    }

    public static OutboxEvent create(
            UUID eventId,
            UUID settlementBatchId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String topic,
            String payload,
            LocalDateTime occurredAt) {
        return new OutboxEvent(
                eventId,
                settlementBatchId,
                aggregateType,
                aggregateId,
                eventType,
                topic,
                payload,
                occurredAt);
    }

    public void markPublished(LocalDateTime publishedAt) {
        verifyPending();
        LocalDateTime attemptedAt = Objects.requireNonNull(publishedAt, "publishedAt은 필수입니다.");
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = attemptedAt;
        this.lastAttemptedAt = attemptedAt;
        this.lastFailureReason = null;
    }

    public void recordPublishFailure(String reason, LocalDateTime attemptedAt, int maxRetryCount) {
        verifyPending();
        if (maxRetryCount <= 0) {
            throw new IllegalArgumentException("maxRetryCount는 1 이상이어야 합니다.");
        }
        this.retryCount++;
        this.lastAttemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt은 필수입니다.");
        this.lastFailureReason = truncate(reason);
        if (retryCount >= maxRetryCount) {
            this.status = OutboxEventStatus.FAILED;
            this.failedAt = attemptedAt;
        }
    }

    public void requeueForRedrive() {
        if (status != OutboxEventStatus.FAILED) {
            throw new OutboxEventInvalidStateException(status);
        }
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
        this.lastAttemptedAt = null;
        this.lastFailureReason = null;
        this.failedAt = null;
        this.publishedAt = null;
    }

    public boolean isPending() {
        return status == OutboxEventStatus.PENDING;
    }

    private void verifyPending() {
        if (!isPending()) {
            throw new OutboxEventInvalidStateException(status);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
        return value;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAILURE_REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
