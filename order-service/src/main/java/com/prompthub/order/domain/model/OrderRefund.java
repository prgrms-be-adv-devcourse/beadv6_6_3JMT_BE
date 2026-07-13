package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "order_refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderRefund {

	private static final int MAX_REASON_LENGTH = 500;

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
	private UUID paymentId;

	@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
	private UUID buyerId;

	@Column(name = "total_refund_amount", nullable = false)
	private int totalRefundAmount;

	@Column(name = "reason", length = MAX_REASON_LENGTH)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private OrderRefundStatus status;

	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_reason", length = 1000)
	private String failureReason;

	@Column(name = "reconciliation_attempt", nullable = false)
	private int reconciliationAttempt;

	@Column(name = "next_check_at")
	private LocalDateTime nextCheckAt;

	@Column(name = "manual_review_required", nullable = false)
	private boolean manualReviewRequired;

	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "failed_at")
	private LocalDateTime failedAt;

	@Column(name = "timeout_at")
	private LocalDateTime timeoutAt;

	@OneToMany(mappedBy = "orderRefund", cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<OrderRefundProduct> refundProducts = new ArrayList<>();

	private OrderRefund(
		UUID id,
		Order order,
		UUID paymentId,
		UUID buyerId,
		String reason,
		LocalDateTime requestedAt
	) {
		this.id = id;
		this.order = order;
		this.paymentId = paymentId;
		this.buyerId = buyerId;
		this.reason = normalizeReason(reason);
		this.status = OrderRefundStatus.REQUESTED;
		this.requestedAt = requestedAt;
		this.nextCheckAt = requestedAt.plusSeconds(65);
	}

	public static OrderRefund create(
		UUID id,
		Order order,
		UUID paymentId,
		UUID buyerId,
		String reason,
		LocalDateTime requestedAt
	) {
		return new OrderRefund(id, order, paymentId, buyerId, reason, requestedAt);
	}

	public void addProduct(OrderProduct orderProduct) {
		if (refundProducts.stream().anyMatch(item -> item.getOrderProduct().getId().equals(orderProduct.getId()))) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
		OrderRefundProduct refundProduct = OrderRefundProduct.create(this, orderProduct);
		refundProducts.add(refundProduct);
		totalRefundAmount += refundProduct.getRefundAmount();
	}

	public void complete(LocalDateTime completedAt) {
		validateResolvable();
		refundProducts.forEach(item -> item.getOrderProduct().completeRefund(completedAt));
		status = OrderRefundStatus.COMPLETED;
		this.completedAt = completedAt;
		nextCheckAt = null;
		manualReviewRequired = false;
	}

	public void fail(String failureCode, String failureReason, LocalDateTime failedAt) {
		validateResolvable();
		refundProducts.forEach(item -> item.getOrderProduct().failRefund());
		status = OrderRefundStatus.FAILED;
		this.failureCode = failureCode;
		this.failureReason = failureReason;
		this.failedAt = failedAt;
		nextCheckAt = null;
		manualReviewRequired = false;
	}

	public void timeout(LocalDateTime timeoutAt) {
		if (status != OrderRefundStatus.REQUESTED) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
		refundProducts.forEach(item -> item.getOrderProduct().markRefundTimeout());
		status = OrderRefundStatus.TIMEOUT;
		this.timeoutAt = timeoutAt;
		manualReviewRequired = true;
		nextCheckAt = null;
	}

	public void claimUntil(LocalDateTime leaseUntil) {
		if (status != OrderRefundStatus.REQUESTED) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
		nextCheckAt = leaseUntil;
	}

	public void recordReconciliationAttempt(LocalDateTime nextCheckAt) {
		if (status != OrderRefundStatus.REQUESTED) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
		reconciliationAttempt++;
		this.nextCheckAt = nextCheckAt;
	}

	public UUID getOrderId() {
		return order.getId();
	}

	public void validateResult(UUID paymentId, UUID orderId, int totalRefundAmount) {
		if (!this.paymentId.equals(paymentId)
			|| !getOrderId().equals(orderId)
			|| this.totalRefundAmount != totalRefundAmount) {
			throw new OrderException(ErrorCode.ORDER_REFUND_EVENT_MISMATCH);
		}
	}

	private void validateResolvable() {
		if (status != OrderRefundStatus.REQUESTED && status != OrderRefundStatus.TIMEOUT) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
	}

	private static String normalizeReason(String reason) {
		if (reason == null || reason.trim().isEmpty()) {
			return null;
		}
		String normalized = reason.trim();
		if (normalized.length() > MAX_REASON_LENGTH) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
		return normalized;
	}
}
