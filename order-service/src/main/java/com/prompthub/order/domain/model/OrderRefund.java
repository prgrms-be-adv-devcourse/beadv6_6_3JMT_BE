package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.persistence.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "order_refund", indexes = {
    @Index(name = "idx_order_refund_payment", columnList = "payment_id"),
    @Index(name = "idx_order_refund_order", columnList = "order_id"),
    @Index(name = "idx_order_refund_status_next_check", columnList = "status, next_check_at"),
    @Index(name = "idx_order_refund_manual_review", columnList = "manual_review_required")
})
@NoArgsConstructor(access = PROTECTED)
public class OrderRefund extends BaseEntity {

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false)
    private UUID orderId;

    @Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
    private UUID paymentId;

    @Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
    private UUID buyerId;

    @Column(name = "total_refund_amount", nullable = false)
    private int totalRefundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderRefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "retryable", nullable = false)
    private boolean retryable;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    @Column(name = "reconciliation_attempt", nullable = false)
    private int reconciliationAttempt;

    @Column(name = "manual_review_required", nullable = false)
    private boolean manualReviewRequired;

    @OneToMany(mappedBy = "orderRefund", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<OrderRefundProduct> products = new ArrayList<>();

    private OrderRefund(
        UUID id,
        UUID orderId,
        UUID paymentId,
        UUID buyerId,
        int totalRefundAmount,
        LocalDateTime requestedAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.buyerId = buyerId;
        this.totalRefundAmount = totalRefundAmount;
        this.status = OrderRefundStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.nextCheckAt = requestedAt.plusMinutes(2);
    }

    public static OrderRefund request(
        UUID orderId,
        UUID paymentId,
        UUID buyerId,
        List<OrderProduct> products,
        LocalDateTime requestedAt
    ) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(buyerId, "buyerId must not be null");
        Objects.requireNonNull(products, "products must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (products.isEmpty()) {
            throw new IllegalArgumentException("products must not be empty");
        }

        Set<UUID> uniqueProductIds = new HashSet<>();
        int totalRefundAmount = 0;
        for (OrderProduct product : products) {
            Objects.requireNonNull(product, "product must not be null");
            UUID productId = product.getId();
            if (productId == null) {
                throw new IllegalArgumentException("order product id must not be null");
            }
            if (!uniqueProductIds.add(productId)) {
                throw new IllegalArgumentException("duplicate order product id: " + productId);
            }
            int productAmount = product.getProductAmount();
            if (productAmount <= 0) {
                throw new IllegalArgumentException("refund amount must be positive");
            }
            totalRefundAmount = Math.addExact(totalRefundAmount, productAmount);
        }

        OrderRefund refund = new OrderRefund(
            UUID.randomUUID(), orderId, paymentId, buyerId, totalRefundAmount, requestedAt
        );
        products.stream()
            .map(product -> OrderRefundProduct.from(refund, product))
            .forEach(refund.products::add);
        return refund;
    }

    public List<OrderRefundProduct> getProducts() {
        return List.copyOf(products);
    }

    public Set<UUID> productIds() {
        return products.stream()
            .map(OrderRefundProduct::getOrderProductId)
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasExactProducts(UUID paymentId, Set<UUID> productIds) {
        return this.paymentId.equals(paymentId) && productIds().equals(productIds);
    }

    public boolean overlaps(Set<UUID> productIds) {
        return productIds.stream().anyMatch(productIds()::contains);
    }

    public void markProcessing(LocalDateTime nextCheckAt) {
        requireNonTerminal();
        Objects.requireNonNull(nextCheckAt, "nextCheckAt must not be null");
        this.status = OrderRefundStatus.PROCESSING;
        this.nextCheckAt = nextCheckAt;
    }

    public void complete(LocalDateTime completedAt) {
        if (status == OrderRefundStatus.COMPLETED) {
            if (Objects.equals(this.completedAt, completedAt)) {
                return;
            }
            throw new IllegalStateException("completed refund result conflicts with stored result");
        }
        requireNonTerminal();
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.status = OrderRefundStatus.COMPLETED;
        this.completedAt = completedAt;
        this.nextCheckAt = null;
    }

    public void fail(
        String code,
        String reason,
        boolean retryable,
        LocalDateTime failedAt
    ) {
        if (status == OrderRefundStatus.FAILED) {
            if (Objects.equals(this.failureCode, code)
                && Objects.equals(this.failureReason, reason)
                && this.retryable == retryable
                && Objects.equals(this.failedAt, failedAt)) {
                return;
            }
            throw new IllegalStateException("failed refund result conflicts with stored result");
        }
        requireNonTerminal();
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        this.status = OrderRefundStatus.FAILED;
        this.failureCode = code;
        this.failureReason = reason;
        this.retryable = retryable;
        this.failedAt = failedAt;
        this.nextCheckAt = null;
    }

    public void markUnknown(LocalDateTime nextCheckAt) {
        requireNonTerminal();
        Objects.requireNonNull(nextCheckAt, "nextCheckAt must not be null");
        this.status = OrderRefundStatus.UNKNOWN;
        this.nextCheckAt = nextCheckAt;
    }

    public void leaseUntil(LocalDateTime leaseUntil) {
        requireNonTerminal();
        this.nextCheckAt = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
    }

    public void scheduleNext(int attempt, LocalDateTime nextCheckAt) {
        requireNonTerminal();
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        Objects.requireNonNull(nextCheckAt, "nextCheckAt must not be null");
        this.reconciliationAttempt = attempt;
        this.nextCheckAt = nextCheckAt;
    }

    public void requireManualReview() {
        requireNonTerminal();
        this.manualReviewRequired = true;
        this.nextCheckAt = null;
    }

    public void requireMatches(UUID paymentId, UUID orderId, int totalRefundAmount) {
        if (!this.paymentId.equals(paymentId)
            || !this.orderId.equals(orderId)
            || this.totalRefundAmount != totalRefundAmount) {
            throw new OrderException(ErrorCode.ORDER_REFUND_EVENT_MISMATCH);
        }
    }

    private void requireNonTerminal() {
        if (status == OrderRefundStatus.COMPLETED || status == OrderRefundStatus.FAILED) {
            throw new IllegalStateException("terminal refund cannot be overwritten");
        }
    }
}
