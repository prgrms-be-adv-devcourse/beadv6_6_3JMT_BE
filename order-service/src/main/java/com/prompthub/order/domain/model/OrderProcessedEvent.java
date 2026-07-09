package com.prompthub.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
    name = "order_processed_event",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_order_processed_event_id_group",
            columnNames = {"event_id", "consumer_group"}
        )
    },
    indexes = {
        @Index(
            name = "idx_order_processed_event_processed_at",
            columnList = "processed_at"
        ),
        @Index(
            name = "idx_order_processed_event_event_type",
            columnList = "event_type"
        )
    }
)
@NoArgsConstructor(access = PROTECTED)
public class OrderProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", columnDefinition = "uuid", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", length = 100, nullable = false)
    private String consumerGroup;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "event_occurred_at", nullable = false)
    private LocalDateTime eventOccurredAt;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    private OrderProcessedEvent(
        UUID eventId,
        String consumerGroup,
        String eventType,
        LocalDateTime eventOccurredAt,
        LocalDateTime processedAt
    ) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.eventType = eventType;
        this.eventOccurredAt = eventOccurredAt;
        this.processedAt = processedAt;
    }

    public static OrderProcessedEvent create(
        UUID eventId,
        String consumerGroup,
        String eventType,
        LocalDateTime eventOccurredAt
    ) {
        return new OrderProcessedEvent(
            eventId,
            consumerGroup,
            eventType,
            eventOccurredAt,
            LocalDateTime.now()
        );
    }
}
