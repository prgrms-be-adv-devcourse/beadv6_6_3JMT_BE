package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
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
    private OrderStatus orderProductStatus;

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
            OrderStatus orderProductStatus,
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
        this.orderProductStatus = orderProductStatus;
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
                OrderStatus.PENDING,
                now,
                now,
                false
        );
    }

    protected void assignOrder(Order order) {
        this.order = order;
    }

    public void markPaid() {
        validateTransition(OrderStatus.PAID);

        this.orderProductStatus = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        validateTransition(OrderStatus.FAILED);

        this.orderProductStatus = OrderStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        cancel(LocalDateTime.now());
    }

    public void cancel(LocalDateTime canceledAt) {
        if (this.orderProductStatus != OrderStatus.PAID) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderProductStatus = OrderStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void expirePending(LocalDateTime canceledAt) {
        if (this.orderProductStatus != OrderStatus.PENDING) {
            return;
        }

        this.orderProductStatus = OrderStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCanceled(LocalDateTime canceledAt) {
        if (this.orderProductStatus != OrderStatus.PENDING) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderProductStatus = OrderStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.updatedAt = LocalDateTime.now();
    }


    public void refund() {
        refund(LocalDateTime.now());
    }

    public void refund(LocalDateTime refundedAt) {
        if (this.orderProductStatus != OrderStatus.PAID) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
        validateRefundedAt(refundedAt);

        this.orderProductStatus = OrderStatus.REFUNDED;
        this.refundedAt = refundedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void requestRefund() {
        validateRefundRequest();
        orderProductStatus = OrderStatus.REFUND_REQUESTED;
        updatedAt = LocalDateTime.now();
    }

    void validateRefundRequest() {
        if (orderProductStatus != OrderStatus.PAID || downloaded || productAmount <= 0) {
            throw new OrderException(downloaded
                    ? ErrorCode.ORDER_PRODUCT_ALREADY_DOWNLOADED
                    : ErrorCode.ORDER_PRODUCT_REFUND_NOT_ALLOWED);
        }
    }

    public void completeRefund(LocalDateTime refundedAt) {
        validateCompleteRefund(refundedAt);
        orderProductStatus = OrderStatus.REFUNDED;
        this.refundedAt = refundedAt;
        updatedAt = LocalDateTime.now();
    }

    void validateCompleteRefund(LocalDateTime refundedAt) {
        if (orderProductStatus != OrderStatus.REFUND_REQUESTED) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
        validateRefundedAt(refundedAt);
    }

    public void restorePaidAfterRefundFailure() {
        validateRestorePaidAfterRefundFailure();
        orderProductStatus = OrderStatus.PAID;
        updatedAt = LocalDateTime.now();
    }

    void validateRestorePaidAfterRefundFailure() {
        if (orderProductStatus != OrderStatus.REFUND_REQUESTED) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
    }

    public boolean canAccessContent() {
        return orderProductStatus == OrderStatus.PAID;
    }

    public void markDownloaded() {
        if (this.downloaded) {
            return;
        }

        this.downloaded = true;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return this.orderProductStatus == OrderStatus.PAID;
    }

    public boolean isRefundable() {
        return this.orderProductStatus == OrderStatus.PAID && !this.downloaded;
    }

    private void validateTransition(OrderStatus target) {
        if (!this.orderProductStatus.canOrderProductTransitionTo(target)) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
    }

    private void validateRefundedAt(LocalDateTime refundedAt) {
        if (refundedAt == null) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
