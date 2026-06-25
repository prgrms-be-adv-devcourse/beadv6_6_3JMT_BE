package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
	name = "outbox_event",
	indexes = @Index(
		name = "idx_outbox_event_status_occurred_at",
		columnList = "status, occurred_at"
	)
)
@NoArgsConstructor(access = PROTECTED)
public class OutboxEvent extends BaseEntity {

	private static final String ORDER_PAID = "ORDER_PAID";
	private static final String ORDER_REFUND = "ORDER_REFUND";
	private static final String ORDER = "ORDER";
	private static final String ORDER_EVENTS_TOPIC = "order-events";

	@Id
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "aggregate_id", columnDefinition = "char(36)", nullable = false)
	private UUID aggregateId;

	@Column(name = "aggregate_type", length = 50, nullable = false)
	private String aggregateType;

	@Column(name = "event_type", length = 100, nullable = false)
	private String eventType;

	@Column(name = "topic", length = 100, nullable = false)
	private String topic;

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
		UUID id,
		UUID aggregateId,
		String aggregateType,
		String eventType,
		String topic,
		String payload,
		OutboxEventStatus status,
		int retryCount,
		LocalDateTime occurredAt,
		LocalDateTime publishedAt
	) {
		this.id = id;
		this.aggregateId = aggregateId;
		this.aggregateType = aggregateType;
		this.eventType = eventType;
		this.topic = topic;
		this.payload = payload;
		this.status = status;
		this.retryCount = retryCount;
		this.occurredAt = occurredAt;
		this.publishedAt = publishedAt;
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
		return new OutboxEvent(
			eventId,
			orderId,
			ORDER,
			ORDER_PAID,
			ORDER_EVENTS_TOPIC,
			payload,
			OutboxEventStatus.PENDING,
			0,
			occurredAt,
			null
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
		return new OutboxEvent(
			eventId,
			orderId,
			ORDER,
			ORDER_REFUND,
			ORDER_EVENTS_TOPIC,
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
