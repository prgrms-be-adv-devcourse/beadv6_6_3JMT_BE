package com.prompthub.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "order_outbox_redrive_history")
@NoArgsConstructor(access = PROTECTED)
public class OutboxRedriveHistory {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "event_id", columnDefinition = "uuid", nullable = false)
    private UUID eventId;

    @Column(name = "requested_by", columnDefinition = "uuid", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "previous_retry_count", nullable = false)
    private int previousRetryCount;

    @Column(name = "previous_last_attempt_at")
    private LocalDateTime previousLastAttemptAt;

    @Column(name = "previous_last_error", columnDefinition = "text")
    private String previousLastError;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    private OutboxRedriveHistory(
        UUID id,
        UUID eventId,
        UUID requestedBy,
        String reason,
        int previousRetryCount,
        LocalDateTime previousLastAttemptAt,
        String previousLastError,
        LocalDateTime requestedAt
    ) {
        this.id = id;
        this.eventId = eventId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.previousRetryCount = previousRetryCount;
        this.previousLastAttemptAt = previousLastAttemptAt;
        this.previousLastError = previousLastError;
        this.requestedAt = requestedAt;
    }

    public static OutboxRedriveHistory create(
        OutboxEvent event,
        UUID requestedBy,
        String reason,
        LocalDateTime requestedAt
    ) {
        validateReason(reason);
        return new OutboxRedriveHistory(
            UUID.randomUUID(), event.getEventId(), requestedBy, reason,
            event.getRetryCount(), event.getLastAttemptAt(), event.getLastError(), requestedAt
        );
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Redrive reason must not be blank");
        }
        if (reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Redrive reason must not exceed 500 characters");
        }
    }
}
