package com.prompthub.order.domain.model;

import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
    name = "order_refund_product",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_order_refund_product_refund_product",
        columnNames = {"order_refund_id", "order_product_id"}
    ),
    indexes = @Index(
        name = "idx_order_refund_product_order_product",
        columnList = "order_product_id"
    )
)
@NoArgsConstructor(access = PROTECTED)
public class OrderRefundProduct extends BaseEntity {

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_refund_id", nullable = false)
    private OrderRefund orderRefund;

    @Column(name = "order_product_id", columnDefinition = "uuid", nullable = false)
    private UUID orderProductId;

    @Column(name = "refund_amount", nullable = false)
    private int refundAmount;

    private OrderRefundProduct(
        UUID id,
        OrderRefund orderRefund,
        UUID orderProductId,
        int refundAmount
    ) {
        this.id = id;
        this.orderRefund = orderRefund;
        this.orderProductId = orderProductId;
        this.refundAmount = refundAmount;
    }

    static OrderRefundProduct from(OrderRefund orderRefund, OrderProduct orderProduct) {
        return new OrderRefundProduct(
            UUID.randomUUID(),
            orderRefund,
            orderProduct.getId(),
            orderProduct.getProductAmount()
        );
    }
}
