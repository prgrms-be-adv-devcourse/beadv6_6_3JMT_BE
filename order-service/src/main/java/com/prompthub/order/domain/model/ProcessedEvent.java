package com.prompthub.order.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
	name = "processed_event",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_processed_event_id_group",
			columnNames = {"event_id", "consumer_group"}
		)
	}
)
@NoArgsConstructor(access = PROTECTED)
public class ProcessedEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", columnDefinition = "uuid", nullable = false)
	private UUID eventId;

	@Column(name = "consumer_group", length = 100, nullable = false)
	private String consumerGroup;

	@Column(name = "event_type", length = 100, nullable = false)
	private String eventType;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	private ProcessedEvent(UUID eventId, String consumerGroup, String eventType, LocalDateTime occurredAt) {
		this.eventId = eventId;
		this.consumerGroup = consumerGroup;
		this.eventType = eventType;
		this.occurredAt = occurredAt;
	}

	public static ProcessedEvent create(UUID eventId, String consumerGroup, String eventType,
		LocalDateTime occurredAt) {
		return new ProcessedEvent(eventId, consumerGroup, eventType, occurredAt);
	}
}
