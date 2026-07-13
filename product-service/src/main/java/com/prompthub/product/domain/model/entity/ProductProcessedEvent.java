package com.prompthub.product.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Kafka 소비 멱등성 이력. eventId + consumerGroup 단위로 이미 처리한 이벤트를 기록해 재처리를 막는다.
 * (kafka-event.md §7)
 */
@Getter
@Entity
@Table(
	name = "product_processed_event",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_product_processed_event_id_group",
			columnNames = {"event_id", "consumer_group"}
		)
	},
	indexes = {
		@Index(name = "idx_product_processed_event_processed_at", columnList = "processed_at")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductProcessedEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", columnDefinition = "uuid", nullable = false)
	private UUID eventId;

	@Column(name = "consumer_group", length = 100, nullable = false)
	private String consumerGroup;

	@Column(name = "event_type", length = 100, nullable = false)
	private String eventType;

	@Column(name = "event_occurred_at")
	private LocalDateTime eventOccurredAt;

	@Column(name = "processed_at", nullable = false)
	private LocalDateTime processedAt;

	private ProductProcessedEvent(UUID eventId, String consumerGroup, String eventType, LocalDateTime eventOccurredAt) {
		this.eventId = eventId;
		this.consumerGroup = consumerGroup;
		this.eventType = eventType;
		this.eventOccurredAt = eventOccurredAt;
		this.processedAt = LocalDateTime.now();
	}

	public static ProductProcessedEvent create(
		UUID eventId, String consumerGroup, String eventType, LocalDateTime eventOccurredAt) {
		return new ProductProcessedEvent(eventId, consumerGroup, eventType, eventOccurredAt);
	}
}
