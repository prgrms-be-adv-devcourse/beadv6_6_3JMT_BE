package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderStatus;
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
    @Column(name = "id", columnDefinition = "char(36)")
    private UUID id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", columnDefinition = "char(36)", nullable = false)
    private UUID productId;

    @Column(name = "seller_id", columnDefinition = "char(36)", nullable = false)
    private UUID sellerId;

    @Column(name = "product_title_snapshot", length = 200, nullable = false)
    private String productTitleSnapshot;

    @Column(name = "product_type_snapshot", length = 30, nullable = false)
    private String productTypeSnapshot;

    @Column(name = "product_amount_snapshot", nullable = false)
    private int productAmountSnapshot;

    @Enumerated(STRING)
    @Column(name = "order_product_status", length = 20, nullable = false)
    private OrderStatus orderStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_download", nullable = false)
    private boolean download;

    private OrderProduct(
            UUID id,
            UUID productId,
            UUID sellerId,
            String productTitleSnapshot,
            String productTypeSnapshot,
            int productAmountSnapshot,
            OrderStatus orderStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean download
    ) {
        this.id = id;
        this.productId = productId;
        this.sellerId = sellerId;
        this.productTitleSnapshot = productTitleSnapshot;
        this.productTypeSnapshot = productTypeSnapshot;
        this.productAmountSnapshot = productAmountSnapshot;
        this.orderStatus = orderStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.download = download;
    }

    public static OrderProduct create(
            UUID productId,
            UUID sellerId,
            String productTitleSnapshot,
            String productTypeSnapshot,
            int productAmountSnapshot
    ) {
        LocalDateTime now = LocalDateTime.now();

        return new OrderProduct(
                UUID.randomUUID(),
                productId,
                sellerId,
                productTitleSnapshot,
                productTypeSnapshot,
                productAmountSnapshot,
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
        validatePending();

        this.orderStatus = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        validatePending();

        this.orderStatus = OrderStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        validatePending();

        this.orderStatus = OrderStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void refund() {
        if (this.orderStatus != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료 상태의 주문 상품만 환불할 수 있습니다.");
        }

        this.orderStatus = OrderStatus.REFUNDED;
        this.refundedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markDownloaded() {
        if (this.orderStatus != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료된 주문 상품만 다운로드 처리할 수 있습니다.");
        }

        this.download = true;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return this.orderStatus == OrderStatus.PAID;
    }

    private void validatePending() {
        if (this.orderStatus != OrderStatus.PENDING) {
            throw new IllegalStateException("대기 상태의 주문 상품만 처리할 수 있습니다.");
        }
    }
}