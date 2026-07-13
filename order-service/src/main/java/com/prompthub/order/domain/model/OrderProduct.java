package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "\"order_product\"")
@NoArgsConstructor(access = PROTECTED)
public class OrderProduct {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", columnDefinition = "uuid", nullable = false)
    private UUID productId;

    @Column(name = "seller_id", columnDefinition = "uuid", nullable = false)
    private UUID sellerId;

    @Column(name = "product_title_snapshot", length = 200, nullable = false)
    private String productTitle;

    @Column(name = "product_type_snapshot", length = 30, nullable = false)
    private String productType;

    @Column(name = "product_model_snapshot", length = 50)
    private String productModel;

    @Column(name = "product_amount_snapshot", nullable = false)
    private int productAmount;

    @Enumerated(STRING)
    @Column(name = "order_product_status", length = 20, nullable = false)
    private OrderProductStatus orderStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "downloaded", nullable = false)
    private boolean downloaded;

    private OrderProduct(
            UUID id,
            UUID productId,
            UUID sellerId,
            String productTitle,
            String productType,
            String productModel,
            int productAmount,
            OrderProductStatus orderStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean downloaded
    ) {
        this.id = id;
        this.productId = productId;
        this.sellerId = sellerId;
        this.productTitle = productTitle;
        this.productType = productType;
        this.productModel = productModel;
        this.productAmount = productAmount;
        this.orderStatus = orderStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.downloaded = downloaded;
    }

    public static OrderProduct create(
            UUID productId,
            UUID sellerId,
            String productTitle,
            String productType,
            String productModel,
            int productAmount
    ) {
        LocalDateTime now = LocalDateTime.now();

        return new OrderProduct(
                UUID.randomUUID(),
                productId,
                sellerId,
                productTitle,
                productType,
                productModel,
                productAmount,
                OrderProductStatus.PENDING,
                now,
                now,
                false
        );
    }

    protected void assignOrder(Order order) {
        this.order = order;
    }

    public void markPaid() {
        validateTransition(OrderProductStatus.PAID);

        this.orderStatus = OrderProductStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        validateTransition(OrderProductStatus.FAILED);

        this.orderStatus = OrderProductStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        cancel(LocalDateTime.now());
    }

    public void cancel(LocalDateTime canceledAt) {
        if (this.orderStatus != OrderProductStatus.PAID) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderStatus = OrderProductStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void expirePending(LocalDateTime canceledAt) {
        if (this.orderStatus != OrderProductStatus.PENDING) {
            return;
        }

        this.orderStatus = OrderProductStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCanceled(LocalDateTime canceledAt) {
        if (this.orderStatus != OrderProductStatus.PENDING) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderStatus = OrderProductStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }


    public void refund() {
        refund(LocalDateTime.now());
    }

    public void refund(LocalDateTime refundedAt) {
        if (this.orderStatus != OrderProductStatus.PAID) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderStatus = OrderProductStatus.REFUNDED;
        this.refundedAt = refundedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDownloaded() {
        if (this.downloaded) {
            return;
        }

        this.downloaded = true;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return this.orderStatus == OrderProductStatus.PAID;
    }

    public boolean isRefundable() {
        return this.orderStatus == OrderProductStatus.PAID && !this.downloaded && this.productAmount > 0;
    }

    public OrderProductStatus getOrderProductStatus() {
        return this.orderStatus;
    }

    public void requestRefund() {
        if (!isRefundable()) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        transitionTo(OrderProductStatus.REFUND_REQUESTED);
    }

    public void completeRefund(LocalDateTime refundedAt) {
        transitionTo(OrderProductStatus.REFUNDED);
        this.refundedAt = refundedAt;
    }

    public void failRefund() {
        transitionTo(OrderProductStatus.REFUND_FAILED);
    }

    public void markRefundTimeout() {
        transitionTo(OrderProductStatus.REFUND_TIMEOUT);
    }

    private void transitionTo(OrderProductStatus target) {
        validateTransition(target);
        this.orderStatus = target;
        this.updatedAt = LocalDateTime.now();
    }

    private void validateTransition(OrderProductStatus target) {
        if (!this.orderStatus.canTransitionTo(target)) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
    }
}
