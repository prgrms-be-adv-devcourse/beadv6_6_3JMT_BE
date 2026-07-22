package com.prompthub.admin.product.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * product_service.product_outbox_event 테이블에 매핑되는 admin-service 전용 엔티티.
 * admin-service는 ddl-auto=none이라 이 테이블을 만들 수 없다 — 소유·폴링은
 * product-service의 OutboxRelay가 담당하고, admin-service는 승인/복귀 트랜잭션
 * 끝에 행을 insert만 한다. (2026-07-21-admin-product-onsale-event-design.md)
 */
@Getter
@Entity
@Table(name = "product_outbox_event", schema = "product_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

	@Column(name = "status", length = 20, nullable = false)
	private String status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	private OutboxEvent(UUID eventId, UUID aggregateId, String eventType, String payload, LocalDateTime occurredAt) {
		this.eventId = eventId;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = "PENDING";
		this.retryCount = 0;
		this.occurredAt = occurredAt;
	}

	public static OutboxEvent create(UUID aggregateId, String eventType, String payload) {
		return new OutboxEvent(UUID.randomUUID(), aggregateId, eventType, payload, LocalDateTime.now());
	}
}
