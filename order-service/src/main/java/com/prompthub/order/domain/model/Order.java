package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static jakarta.persistence.CascadeType.ALL;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
    name = "\"order\"",
    indexes = {
        @Index(name = "idx_order_buyer_created_at", columnList = "buyer_id, created_at DESC"),
        @Index(name = "idx_order_status_created_at", columnList = "order_status, created_at DESC"),
        @Index(name = "idx_order_completed_at", columnList = "completed_at"),
        @Index(name = "idx_order_refunded_at", columnList = "refunded_at")
    }
)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", length = 20, nullable = false)
    private OrderStatus orderStatus;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Transient
    private LocalDateTime legacyCanceledAt;

    @OneToMany(mappedBy = "order", cascade = ALL, orphanRemoval = true)
    private final List<OrderProduct> orderProducts = new ArrayList<>();

    private Order(
        UUID id,
        UUID buyerId,
        String orderNumber,
        int totalOrderAmount,
        OrderStatus orderStatus
    ) {
        this.id = id;
        this.buyerId = buyerId;
        this.orderNumber = orderNumber;
        this.totalOrderAmount = totalOrderAmount;
        this.orderStatus = orderStatus;
    }

    public static Order create(
        UUID buyerId,
        String orderNumber,
        int totalOrderAmount
    ) {
        return new Order(
            UUID.randomUUID(),
            buyerId,
            orderNumber,
            totalOrderAmount,
            OrderStatus.CREATED
        );
    }


    public void addOrderProduct(OrderProduct orderProduct) {
        this.orderProducts.add(orderProduct);
        orderProduct.assignOrder(this);
    }

    public void markCompleted() {
        markCompleted(LocalDateTime.now());
    }

    public void markCompleted(LocalDateTime completedAt) {
        validateTransition(OrderStatus.COMPLETED);

        this.orderStatus = OrderStatus.COMPLETED;
        this.completedAt = completedAt;
        this.orderProducts.forEach(OrderProduct::markPaid);
    }

    public void markPaid() {
        markCompleted();
    }

    public void markPaid(LocalDateTime completedAt) {
        markCompleted(completedAt);
    }

    public void markFailed() {
        validateTransition(OrderStatus.FAILED);

        this.orderStatus = OrderStatus.FAILED;
        this.orderProducts.forEach(OrderProduct::markFailed);
    }

    public void cancel() {
        markCanceled(LocalDateTime.now());
    }

    public void cancel(LocalDateTime canceledAt) {
        markCanceled(canceledAt);
    }

    public void markCanceled() {
        markCanceled(LocalDateTime.now());
    }

    public void markCanceled(LocalDateTime canceledAt) {
        markFailed();
        this.legacyCanceledAt = canceledAt;
    }

    public Optional<OrderProduct> refundOrderProduct(
        UUID orderProductId,
        int refundAmount,
        LocalDateTime refundedAt
    ) {
        OrderProduct target = this.orderProducts.stream()
            .filter(orderProduct -> orderProduct.getId().equals(orderProductId))
            .findFirst()
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND));
        if (target.getProductAmount() != refundAmount) {
            throw new OrderException(ErrorCode.ORDER_REFUND_AMOUNT_MISMATCH);
        }
        if (target.getOrderStatus() == OrderProductStatus.REFUNDED) {
            return Optional.empty();
        }

        target.refund(refundedAt);
        recalculateRefundStatus(refundedAt);
        return Optional.of(target);
    }

    public void recalculateRefundStatus(LocalDateTime refundedAt) {
        long refundedCount = this.orderProducts.stream()
            .filter(product -> product.getOrderStatus() == OrderProductStatus.REFUNDED)
            .count();

        if (refundedCount == 0) {
            return;
        }

        OrderStatus target = refundedCount == this.orderProducts.size()
            ? OrderStatus.ALL_REFUNDED
            : OrderStatus.PARTIAL_REFUNDED;
        if (this.orderStatus == target) {
            return;
        }
        validateTransition(target);
        this.orderStatus = target;

        if (target == OrderStatus.ALL_REFUNDED) {
            this.refundedAt = refundedAt;
        }
    }

    public void expirePending(LocalDateTime expiredAt) {
        if (this.orderStatus != OrderStatus.CREATED) {
            return;
        }

        markFailed();
        this.legacyCanceledAt = expiredAt;
    }

    public boolean isExpired(LocalDateTime now, int expireAfterMinutes) {
        return !getCreatedAt().plusMinutes(expireAfterMinutes).isAfter(now);
    }

    public boolean isPending() {
        return this.orderStatus == OrderStatus.CREATED;
    }

    public boolean isPaid() {
        return this.orderStatus == OrderStatus.COMPLETED;
    }

    public boolean canAccessContent(OrderProduct orderProduct) {
        return (this.orderStatus == OrderStatus.COMPLETED || this.orderStatus == OrderStatus.PARTIAL_REFUNDED)
            && orderProduct.isPaid();
    }

    public int getTotalProductCount() {
        return this.orderProducts.size();
    }

    public LocalDateTime getPaidAt() {
        return this.completedAt;
    }

    public LocalDateTime getCanceledAt() {
        return this.legacyCanceledAt;
    }

    private void validateTransition(OrderStatus target) {
        if (!this.orderStatus.canTransitionTo(target)) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
    }
}
