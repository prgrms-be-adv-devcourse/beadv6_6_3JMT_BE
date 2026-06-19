package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
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
public class Order {

	@Id
	@Column(name = "id", columnDefinition = "char(36)")
	private UUID id;

	@Column(name = "buyer_id", columnDefinition = "char(36)", nullable = false)
	private UUID buyerId;

	@Column(name = "order_number", length = 30, nullable = false, unique = true)
	private String orderNumber;

	@Column(name = "total_order_amount", nullable = false)
	private int totalOrderAmount;

	@Column(name = "total_item_count", nullable = false)
	private int totalItemCount;

	@Enumerated(STRING)
	@Column(name = "order_status", length = 20, nullable = false)
	private OrderStatus orderStatus;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "paid_at")
	private LocalDateTime paidAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(name = "refunded_at")
	private LocalDateTime refundedAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@OneToMany(mappedBy = "order", cascade = ALL, orphanRemoval = true)
	private final List<OrderProduct> orderProducts = new ArrayList<>();

	private Order(
		UUID id,
		UUID buyerId,
		String orderNumber,
		int totalOrderAmount,
		int totalItemCount,
		OrderStatus orderStatus,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {
		this.id = id;
		this.buyerId = buyerId;
		this.orderNumber = orderNumber;
		this.totalOrderAmount = totalOrderAmount;
		this.totalItemCount = totalItemCount;
		this.orderStatus = orderStatus;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Order create(
		UUID buyerId,
		String orderNumber,
		int totalOrderAmount,
		int totalItemCount
	) {
		LocalDateTime now = LocalDateTime.now();
		return new Order(
			UUID.randomUUID(),
			buyerId,
			orderNumber,
			totalOrderAmount,
			totalItemCount,
			PENDING,
			now,
			now
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
		validatePending();

		this.orderStatus = OrderStatus.PAID;
		this.paidAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();

		this.orderProducts.forEach(OrderProduct::markPaid);
	}

	public void markFailed() {
		validatePending();

		this.orderStatus = OrderStatus.FAILED;
		this.updatedAt = LocalDateTime.now();

		this.orderProducts.forEach(OrderProduct::markFailed);
	}

	public void cancel() {
		validatePending();

		this.orderStatus = OrderStatus.CANCELED;
		this.canceledAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();

		this.orderProducts.forEach(OrderProduct::cancel);
	}

	public void refund() {
		if (this.orderStatus != OrderStatus.PAID) {
			throw new IllegalStateException("결제 완료 상태의 주문만 환불할 수 있습니다.");
		}

		this.orderStatus = OrderStatus.REFUNDED;
		this.refundedAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();

		this.orderProducts.forEach(OrderProduct::refund);
	}

	public boolean isPending() {
		return this.orderStatus == PENDING;
	}

	public boolean isPaid() {
		return this.orderStatus == OrderStatus.PAID;
	}

	private void validatePending() {
		if (this.orderStatus != PENDING) {
			throw new IllegalStateException("대기 상태의 주문만 처리할 수 있습니다.");
		}
	}
}