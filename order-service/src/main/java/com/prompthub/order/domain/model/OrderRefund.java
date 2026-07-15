package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
	name = "order_refund",
	indexes = {
		@Index(name = "idx_order_refund_status_next_check", columnList = "status, next_check_at"),
		@Index(name = "idx_order_refund_order_status", columnList = "order_id, status"),
		@Index(name = "idx_order_refund_payment", columnList = "payment_id")
	},
	check = @CheckConstraint(
		name = "ck_order_refund_positive_values",
		constraint = "total_refund_amount > 0 and check_count >= 0"
	)
)
@NoArgsConstructor(access = PROTECTED)
public class OrderRefund extends BaseEntity {

	@Id
	@Column(name = "id", columnDefinition = "uuid", nullable = false)
	private UUID id;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "order_id", columnDefinition = "uuid", nullable = false)
	private UUID orderId;

	@Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
	private UUID paymentId;

	@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
	private UUID buyerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private OrderRefundStatus status;

	@Column(name = "total_refund_amount", nullable = false)
	private int totalRefundAmount;

	@Column(name = "check_count", nullable = false)
	private int checkCount;

	@Column(name = "next_check_at")
	private LocalDateTime nextCheckAt;

	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "failed_at")
	private LocalDateTime failedAt;

	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	@OneToOne(
		mappedBy = "orderRefund",
		fetch = FetchType.LAZY,
		cascade = CascadeType.ALL,
		orphanRemoval = true,
		optional = false
	)
	private OrderRefundProduct product;

	private OrderRefund(
		UUID id,
		UUID orderId,
		UUID paymentId,
		UUID buyerId,
		int totalRefundAmount,
		LocalDateTime requestedAt,
		LocalDateTime nextCheckAt
	) {
		this.id = id;
		this.orderId = orderId;
		this.paymentId = paymentId;
		this.buyerId = buyerId;
		this.status = OrderRefundStatus.REQUESTED;
		this.totalRefundAmount = totalRefundAmount;
		this.requestedAt = requestedAt;
		this.nextCheckAt = nextCheckAt;
	}

	public static OrderRefund request(
		UUID orderId,
		UUID paymentId,
		UUID buyerId,
		UUID orderProductId,
		int refundAmount,
		LocalDateTime requestedAt,
		LocalDateTime nextCheckAt
	) {
		Objects.requireNonNull(orderId, "orderId must not be null");
		Objects.requireNonNull(paymentId, "paymentId must not be null");
		Objects.requireNonNull(buyerId, "buyerId must not be null");
		Objects.requireNonNull(orderProductId, "orderProductId must not be null");
		Objects.requireNonNull(requestedAt, "requestedAt must not be null");
		Objects.requireNonNull(nextCheckAt, "nextCheckAt must not be null");
		if (refundAmount <= 0) {
			throw new IllegalArgumentException("refundAmount must be positive");
		}

		OrderRefund refund = new OrderRefund(
			UUID.randomUUID(),
			orderId,
			paymentId,
			buyerId,
			refundAmount,
			requestedAt,
			nextCheckAt
		);
		refund.product = OrderRefundProduct.create(
			refund,
			orderProductId,
			refundAmount,
			requestedAt
		);
		return refund;
	}

	public void complete(LocalDateTime completedAt) {
		requireResolvable();
		LocalDateTime resolvedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");

		this.status = OrderRefundStatus.COMPLETED;
		this.completedAt = resolvedAt;
		this.nextCheckAt = null;
	}

	public void fail(String failureCode, String failureReason, LocalDateTime failedAt) {
		requireResolvable();
		String resolvedFailureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
		LocalDateTime resolvedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");

		this.status = OrderRefundStatus.FAILED;
		this.failureCode = resolvedFailureCode;
		this.failureReason = failureReason;
		this.failedAt = resolvedAt;
		this.nextCheckAt = null;
	}

	public void moveToDlq() {
		requireRequested();
		this.status = OrderRefundStatus.DLQ;
		this.nextCheckAt = null;
	}

	public void scheduleNextCheck(LocalDateTime nextCheckAt) {
		requireRequested();
		LocalDateTime scheduledAt = Objects.requireNonNull(nextCheckAt, "nextCheckAt must not be null");
		int nextCheckCount = Math.incrementExact(this.checkCount);

		this.checkCount = nextCheckCount;
		this.nextCheckAt = scheduledAt;
	}

	private void requireRequested() {
		if (this.status != OrderRefundStatus.REQUESTED) {
			throw new IllegalStateException("refund must be REQUESTED");
		}
	}

	private void requireResolvable() {
		if (this.status != OrderRefundStatus.REQUESTED
			&& this.status != OrderRefundStatus.DLQ) {
			throw new IllegalStateException("refund result is already final");
		}
	}
}
