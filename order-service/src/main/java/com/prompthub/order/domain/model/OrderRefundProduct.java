package com.prompthub.order.domain.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
	name = "order_refund_product",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_order_refund_product_refund", columnNames = "order_refund_id"),
		@UniqueConstraint(name = "uk_order_refund_product_order_product", columnNames = "order_product_id")
	},
	check = @CheckConstraint(
		name = "ck_order_refund_product_positive_amount",
		constraint = "refund_amount > 0"
	)
)
@NoArgsConstructor(access = PROTECTED)
public class OrderRefundProduct {

	@Id
	@Column(name = "id", columnDefinition = "uuid", nullable = false)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_refund_id", nullable = false, unique = true)
	private OrderRefund orderRefund;

	@Column(name = "order_product_id", columnDefinition = "uuid", nullable = false, unique = true)
	private UUID orderProductId;

	@Column(name = "refund_amount", nullable = false)
	private int refundAmount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private OrderRefundProduct(
		UUID id,
		OrderRefund orderRefund,
		UUID orderProductId,
		int refundAmount,
		LocalDateTime createdAt
	) {
		this.id = id;
		this.orderRefund = orderRefund;
		this.orderProductId = orderProductId;
		this.refundAmount = refundAmount;
		this.createdAt = createdAt;
	}

	static OrderRefundProduct create(
		OrderRefund orderRefund,
		UUID orderProductId,
		int refundAmount,
		LocalDateTime createdAt
	) {
		return new OrderRefundProduct(
			UUID.randomUUID(),
			orderRefund,
			orderProductId,
			refundAmount,
			createdAt
		);
	}
}
