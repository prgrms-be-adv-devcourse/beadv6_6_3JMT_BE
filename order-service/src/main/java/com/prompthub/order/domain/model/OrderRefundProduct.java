package com.prompthub.order.domain.model;

import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(
	name = "order_refund_product",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_order_refund_product_refund_product", columnNames = {"order_refund_id", "order_product_id"}),
		@UniqueConstraint(name = "uk_order_refund_product_order_product", columnNames = "order_product_id")
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderRefundProduct extends BaseEntity {

	@Id
	@GeneratedValue
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_refund_id", nullable = false)
	private OrderRefund orderRefund;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_product_id", nullable = false)
	private OrderProduct orderProduct;

	@Column(name = "refund_amount", nullable = false)
	private int refundAmount;

	private OrderRefundProduct(OrderRefund orderRefund, OrderProduct orderProduct) {
		this.orderRefund = orderRefund;
		this.orderProduct = orderProduct;
		this.refundAmount = orderProduct.getProductAmount();
	}

	public static OrderRefundProduct create(OrderRefund orderRefund, OrderProduct orderProduct) {
		return new OrderRefundProduct(orderRefund, orderProduct);
	}
}
