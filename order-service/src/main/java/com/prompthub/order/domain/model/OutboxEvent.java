package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
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
            name = "idx_order_outbox_event_order_id",
            columnList = "order_id"
        )
    }
)
@NoArgsConstructor(access = PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false)
    private UUID orderId;

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
        UUID orderId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        int retryCount,
        LocalDateTime occurredAt,
        LocalDateTime publishedAt
    ) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.occurredAt = occurredAt;
        this.publishedAt = publishedAt;
    }

    public static OutboxEvent orderCreated(
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return orderCreated(UUID.randomUUID(), orderId, payload, occurredAt);
    }

    public static OutboxEvent orderCreated(
        UUID eventId,
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return create(
            eventId,
            orderId,
            OrderEventType.ORDER_CREATED.code(),
            payload,
            occurredAt
        );
    }

    public static OutboxEvent orderPaid(
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return orderPaid(UUID.randomUUID(), orderId, payload, occurredAt);
    }

    public static OutboxEvent orderPaid(
        UUID eventId,
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return create(
            eventId,
            orderId,
            OrderEventType.ORDER_PAID.code(),
            payload,
            occurredAt
        );
    }

    public static OutboxEvent orderRefund(
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return orderRefund(UUID.randomUUID(), orderId, payload, occurredAt);
    }

    public static OutboxEvent orderRefund(
        UUID eventId,
        UUID orderId,
        String payload,
        LocalDateTime occurredAt
    ) {
        return create(
            eventId,
            orderId,
            OrderEventType.ORDER_REFUND.code(),
            payload,
            occurredAt
        );
    }

    public static OutboxEvent create(
        UUID eventId,
        UUID orderId,
        String eventType,
        String payload,
        LocalDateTime occurredAt
    ) {
        return new OutboxEvent(
            eventId,
            orderId,
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
