package com.prompthub.order.domain.model;

import com.prompthub.order.global.config.BaseEntity;
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
@Table(name = "order_outbox")
@NoArgsConstructor(access = PROTECTED)
public class OrderOutbox extends BaseEntity {

	private static final String ORDER_PAID = "ORDER_PAID";
	private static final String ORDER = "ORDER";
	private static final String PENDING = "PENDING";

	@Id
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@Column(name = "event_type", length = 50, nullable = false)
	private String eventType;

	@Column(name = "aggregate_type", length = 50, nullable = false)
	private String aggregateType;

	@Column(name = "aggregate_id", columnDefinition = "char(36)", nullable = false)
	private UUID aggregateId;

	@Column(name = "payload", columnDefinition = "text", nullable = false)
	private String payload;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "status", length = 20, nullable = false)
	private String status;

	private OrderOutbox(
		UUID id,
		String eventType,
		String aggregateType,
		UUID aggregateId,
		String payload,
		LocalDateTime occurredAt,
		String status
	) {
		this.id = id;
		this.eventType = eventType;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.payload = payload;
		this.occurredAt = occurredAt;
		this.status = status;
	}

	public static OrderOutbox orderPaid(
		UUID orderId,
		String payload,
		LocalDateTime occurredAt
	) {
		return new OrderOutbox(
			UUID.randomUUID(),
			ORDER_PAID,
			ORDER,
			orderId,
			payload,
			occurredAt,
			PENDING
		);
	}
}
