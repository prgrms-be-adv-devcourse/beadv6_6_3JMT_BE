package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "product_title_snapshot", length = 200, nullable = false)
    private String productTitle;

    @Column(name = "product_amount_snapshot", nullable = false)
    private int productAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_product_status", length = 20, nullable = false)
    private OrderProductStatus orderStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "downloaded", nullable = false)
    private boolean downloaded;

    @Transient
    private UUID legacySellerId;

    @Transient
    private String legacyProductType;

    @Transient
    private String legacyProductModel;

    @Transient
    private LocalDateTime legacyCanceledAt;

    private OrderProduct(
        UUID id,
        UUID productId,
        String productTitle,
        int productAmount,
        OrderProductStatus orderStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean downloaded
    ) {
        this.id = id;
        this.productId = productId;
        this.productTitle = productTitle;
        this.productAmount = productAmount;
        this.orderStatus = orderStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.downloaded = downloaded;
    }

    public static OrderProduct create(
        UUID productId,
        String productTitle,
        int productAmount
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new OrderProduct(
            UUID.randomUUID(),
            productId,
            productTitle,
            productAmount,
            OrderProductStatus.PENDING,
            now,
            now,
            false
        );
    }

    public static OrderProduct create(
        UUID productId,
        UUID sellerId,
        String productTitle,
        String productType,
        String productModel,
        int productAmount
    ) {
        OrderProduct orderProduct = create(productId, productTitle, productAmount);
        orderProduct.legacySellerId = sellerId;
        orderProduct.legacyProductType = productType;
        orderProduct.legacyProductModel = productModel;
        return orderProduct;
    }

    protected void assignOrder(Order order) {
        this.order = order;
    }

    public void markPaid() {
        transitionTo(OrderProductStatus.PAID);
    }

    public void markFailed() {
        transitionTo(OrderProductStatus.FAILED);
    }

    public void cancel() {
        throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
    }

    public void cancel(LocalDateTime canceledAt) {
        cancel();
    }

    public void expirePending(LocalDateTime expiredAt) {
        if (this.orderStatus != OrderProductStatus.PENDING) {
            return;
        }
        markFailed();
        this.legacyCanceledAt = expiredAt;
    }

    public void markCanceled(LocalDateTime canceledAt) {
        expirePending(canceledAt);
    }

    public void refund() {
        refund(LocalDateTime.now());
    }

    public void refund(LocalDateTime refundedAt) {
        transitionTo(OrderProductStatus.REFUNDED);
        this.refundedAt = refundedAt;
    }

    public void markDownloaded() {
        if (this.downloaded) {
            return;
        }
        if (!isPaid()) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.downloaded = true;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return this.orderStatus == OrderProductStatus.PAID;
    }

    public boolean isRefundable() {
        return isPaid() && !this.downloaded;
    }

    public UUID getSellerId() {
        if (this.legacySellerId != null) {
            return this.legacySellerId;
        }
        return this.order == null ? null : this.order.getSellerId();
    }

    public String getProductType() {
        return this.legacyProductType;
    }

    public String getProductModel() {
        return this.legacyProductModel;
    }

    public LocalDateTime getCanceledAt() {
        return this.legacyCanceledAt;
    }

    private void transitionTo(OrderProductStatus target) {
        if (!this.orderStatus.canTransitionTo(target)) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        this.orderStatus = target;
        this.updatedAt = LocalDateTime.now();
    }
}
