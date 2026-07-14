package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.domain.enums.OrderStatus.PENDING;
import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "\"order\"")
@NoArgsConstructor(access = PROTECTED)
public class Order extends BaseEntity {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
	private UUID buyerId;

	@Column(name = "order_number", length = 30, nullable = false, unique = true)
	private String orderNumber;

	@Column(name = "total_order_amount", nullable = false)
	private int totalOrderAmount;

	@Column(name = "total_product_count", nullable = false)
	private int totalProductCount;

	@Enumerated(STRING)
	@Column(name = "order_status", length = 20, nullable = false)
	private OrderStatus orderStatus;

	@Column(name = "paid_at")
	private LocalDateTime paidAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(name = "refunded_at")
	private LocalDateTime refundedAt;

	@OneToMany(mappedBy = "order", cascade = ALL, orphanRemoval = true)
	private final List<OrderProduct> orderProducts = new ArrayList<>();

	private Order(
		UUID id,
		UUID buyerId,
		String orderNumber,
		int totalOrderAmount,
		int totalProductCount,
		OrderStatus orderStatus
	) {
		this.id = id;
		this.buyerId = buyerId;
		this.orderNumber = orderNumber;
		this.totalOrderAmount = totalOrderAmount;
		this.totalProductCount = totalProductCount;
		this.orderStatus = orderStatus;
	}

	public static Order create(
		UUID buyerId,
		String orderNumber,
		int totalOrderAmount,
		int totalItemCount
	) {
		return new Order(
			UUID.randomUUID(),
			buyerId,
			orderNumber,
			totalOrderAmount,
			totalItemCount,
			PENDING
		);
	}

	public void updateOrderStatus(OrderStatus status) {
		this.orderStatus = status;
	}

	public void addOrderProduct(OrderProduct orderProduct) {
		this.orderProducts.add(orderProduct);
		orderProduct.assignOrder(this);
	}

	public void markPaid() {
		markPaid(LocalDateTime.now());
	}

	public void markPaid(LocalDateTime paidAt) {
		validateTransition(OrderStatus.PAID);

		this.orderStatus = OrderStatus.PAID;
		this.paidAt = paidAt;
		this.orderProducts.forEach(OrderProduct::markPaid);
	}

	public void markFailed() {
		validateTransition(OrderStatus.FAILED);

		this.orderStatus = OrderStatus.FAILED;
		this.orderProducts.forEach(OrderProduct::markFailed);
	}

	public void cancel() {
		cancel(LocalDateTime.now());
	}

	public void cancel(LocalDateTime canceledAt) {
		markCanceled(canceledAt);
	}

	public void markCanceled() {
		markCanceled(LocalDateTime.now());
	}

	public void markCanceled(LocalDateTime canceledAt) {
		validateTransition(OrderStatus.CANCELED);

		this.orderStatus = OrderStatus.CANCELED;
		this.canceledAt = canceledAt;
		this.orderProducts.forEach(orderProduct -> orderProduct.markCanceled(canceledAt));
	}

	public void refund() {
		refund(LocalDateTime.now());
	}

	public void refund(LocalDateTime refundedAt) {
		if (this.orderStatus != OrderStatus.PAID) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
		validateRefundedAt(refundedAt);

		this.orderStatus = OrderStatus.REFUNDED;
		this.refundedAt = refundedAt;
		this.orderProducts.forEach(orderProduct -> orderProduct.refund(refundedAt));
	}

	public List<OrderProduct> requestRefundProducts(Set<UUID> orderProductIds) {
		if (this.orderStatus != OrderStatus.PAID
			&& this.orderStatus != OrderStatus.PARTIALLY_REFUNDED) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}

		List<OrderProduct> products = requireProducts(orderProductIds);
		products.forEach(OrderProduct::validateRefundRequest);
		products.forEach(OrderProduct::requestRefund);
		return products;
	}

	public void completeRefundProducts(Set<UUID> orderProductIds, LocalDateTime refundedAt) {
		List<OrderProduct> products = requireProducts(orderProductIds);
		validateRefundedAt(refundedAt);
		products.forEach(orderProduct -> orderProduct.validateCompleteRefund(refundedAt));
		products.forEach(orderProduct -> orderProduct.completeRefund(refundedAt));
		recalculateRefundStatus(refundedAt);
	}

	public void restoreRefundProducts(Set<UUID> orderProductIds) {
		List<OrderProduct> products = requireProducts(orderProductIds);
		products.forEach(OrderProduct::validateRestorePaidAfterRefundFailure);
		products.forEach(OrderProduct::restorePaidAfterRefundFailure);
		recalculateRefundStatus(null);
	}

	private List<OrderProduct> requireProducts(Set<UUID> orderProductIds) {
		if (orderProductIds == null || orderProductIds.isEmpty()) {
			throw new OrderException(ErrorCode.ORDER_REFUND_RELATION_MISMATCH);
		}

		List<OrderProduct> products = this.orderProducts.stream()
			.filter(orderProduct -> orderProductIds.contains(orderProduct.getId()))
			.toList();

		if (products.size() != orderProductIds.size()) {
			throw new OrderException(ErrorCode.ORDER_REFUND_RELATION_MISMATCH);
		}

		return products;
	}

	private void recalculateRefundStatus(LocalDateTime refundedAt) {
		long refundedCount = this.orderProducts.stream()
			.filter(orderProduct -> orderProduct.getOrderProductStatus() == OrderStatus.REFUNDED)
			.count();

		if (refundedCount == this.orderProducts.size()) {
			this.orderStatus = OrderStatus.REFUNDED;
			this.refundedAt = refundedAt;
		} else if (refundedCount > 0) {
			this.orderStatus = OrderStatus.PARTIALLY_REFUNDED;
			this.refundedAt = null;
		} else {
			this.orderStatus = OrderStatus.PAID;
			this.refundedAt = null;
		}
	}

	public void expirePending(LocalDateTime canceledAt) {
		if (this.orderStatus != PENDING) {
			return;
		}

		this.orderStatus = OrderStatus.CANCELED;
		this.canceledAt = canceledAt;
		this.orderProducts.forEach(orderProduct -> orderProduct.expirePending(canceledAt));
	}

	public boolean isExpired(LocalDateTime now, int expireAfterMinutes) {
		return !getCreatedAt().plusMinutes(expireAfterMinutes).isAfter(now);
	}

	public boolean isPending() {
		return this.orderStatus == PENDING;
	}

	public boolean isPaid() {
		return this.orderStatus == OrderStatus.PAID;
	}

	private void validateTransition(OrderStatus target) {
		if (!this.orderStatus.canTransitionTo(target)) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
	}

	private void validateRefundedAt(LocalDateTime refundedAt) {
		if (refundedAt == null) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
