package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@Column(name = "buyer_id", columnDefinition = "char(36)", nullable = false)
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
		validatePending();

		this.orderStatus = OrderStatus.PAID;
		this.paidAt = paidAt;
		this.orderProducts.forEach(OrderProduct::markPaid);
	}

	public void markFailed() {
		validatePending();

		this.orderStatus = OrderStatus.FAILED;
		this.orderProducts.forEach(OrderProduct::markFailed);
	}

	public void cancel() {
		cancel(LocalDateTime.now());
	}

	public void cancel(LocalDateTime canceledAt) {
		if (this.orderStatus != OrderStatus.PAID) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION, "결제 완료 상태의 주문만 취소할 수 있습니다.");
		}

		this.orderStatus = OrderStatus.CANCELED;
		this.canceledAt = canceledAt;
		this.orderProducts.forEach(orderProduct -> orderProduct.cancel(canceledAt));
	}

	public void refund() {
		refund(LocalDateTime.now());
	}

	public void refund(LocalDateTime refundedAt) {
		if (this.orderStatus != OrderStatus.PAID) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION, "결제 완료 상태의 주문만 환불할 수 있습니다.");
		}

		this.orderStatus = OrderStatus.REFUNDED;
		this.refundedAt = refundedAt;
		this.orderProducts.forEach(orderProduct -> orderProduct.refund(refundedAt));
	}

	public boolean isPending() {
		return this.orderStatus == PENDING;
	}

	public boolean isPaid() {
		return this.orderStatus == OrderStatus.PAID;
	}

	private void validatePending() {
		if (this.orderStatus != PENDING) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION, "대기 상태의 주문만 처리할 수 있습니다.");
		}
	}
}
