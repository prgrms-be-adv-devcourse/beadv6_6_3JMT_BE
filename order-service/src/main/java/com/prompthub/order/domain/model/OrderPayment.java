package com.prompthub.order.domain.model;

import com.prompthub.order.global.config.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
	name = "order_payment",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_order_payment_order_id", columnNames = "order_id"),
		@UniqueConstraint(name = "uk_order_payment_payment_id", columnNames = "payment_id"),
		@UniqueConstraint(name = "uk_order_payment_pg_tx_id", columnNames = "pg_tx_id")
	}
)
@NoArgsConstructor(access = PROTECTED)
public class OrderPayment extends BaseEntity {

	private static final String UNKNOWN_PAYMENT_METHOD = "UNKNOWN";
	private static final String PAYMENT_EVENT_PROVIDER = "PAYMENT_SERVICE";

	@Id
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@Column(name = "order_id", columnDefinition = "char(36)", nullable = false)
	private UUID orderId;

	@Column(name = "payment_id", columnDefinition = "char(36)", nullable = false)
	private UUID paymentId;

	@Column(name = "buyer_id", columnDefinition = "char(36)", nullable = false)
	private UUID buyerId;

	@Column(name = "pg_tx_id", length = 100, nullable = false)
	private String pgTxId;

	@Column(name = "payment_method", length = 30, nullable = false)
	private String paymentMethod;

	@Column(name = "provider", length = 50, nullable = false)
	private String provider;

	@Column(name = "approved_amount", nullable = false)
	private int approvedAmount;

	@Column(name = "approved_at", nullable = false)
	private LocalDateTime approvedAt;

	private OrderPayment(
		UUID id,
		UUID orderId,
		UUID paymentId,
		UUID buyerId,
		String pgTxId,
		String paymentMethod,
		String provider,
		int approvedAmount,
		LocalDateTime approvedAt
	) {
		this.id = id;
		this.orderId = orderId;
		this.paymentId = paymentId;
		this.buyerId = buyerId;
		this.pgTxId = pgTxId;
		this.paymentMethod = paymentMethod;
		this.provider = provider;
		this.approvedAmount = approvedAmount;
		this.approvedAt = approvedAt;
	}

	public static OrderPayment create(
		UUID orderId,
		UUID paymentId,
		UUID buyerId,
		int approvedAmount,
		LocalDateTime approvedAt
	) {
		return new OrderPayment(
			UUID.randomUUID(),
			orderId,
			paymentId,
			buyerId,
			paymentId.toString(),
			UNKNOWN_PAYMENT_METHOD,
			PAYMENT_EVENT_PROVIDER,
			approvedAmount,
			approvedAt
		);
	}
}
