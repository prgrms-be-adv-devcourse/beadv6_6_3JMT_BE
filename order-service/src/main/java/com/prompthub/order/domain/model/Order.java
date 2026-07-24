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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static jakarta.persistence.CascadeType.ALL;
import static lombok.AccessLevel.PROTECTED;

/**
 * 주문(Order) 도메인 엔티티.
 * <p>
 * 주의: 주문 상태({@link OrderStatus})를 우회하여 직접 변경하는 Setter(예: {@code updateOrderStatus})의 사용을 금지합니다.
 * 상태 변경 시 반드시 {@code markCompleted}, {@code markFailed}, {@code cancel}, {@code refundOrderProduct} 등
 * 도메인 규칙이 캡슐화된 상태 전이 메서드를 사용해야 합니다. 이를 통해 주문 상품 상태 동기화 및 유효성 검증을 보장합니다.
 * </p>
 */
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
        Objects.requireNonNull(orderProduct, "orderProduct must not be null");
        orderProduct.assignOrder(this);
        this.orderProducts.add(orderProduct);
    }

    public boolean isFree() {
        return this.totalOrderAmount == 0;
    }

    public void completeFreeOrder() {
        if (!isFree()) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
        markCompleted();
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
        markFailed(LocalDateTime.now());
    }

    public void markFailed(LocalDateTime failedAt) {
        validateTransition(OrderStatus.FAILED);

        this.orderProducts.forEach(product -> product.expirePending(failedAt));
        this.orderStatus = OrderStatus.FAILED;
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
        markFailed(canceledAt);
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

        requestRefund(List.of(orderProductId));
        completeRequestedRefund(refundAmount, refundedAt);
        return Optional.of(target);
    }

    public void requestRefund(List<UUID> orderProductIds) {
        if (this.orderStatus != OrderStatus.COMPLETED
            && this.orderStatus != OrderStatus.PARTIAL_REFUNDED) {
            throw new OrderException(ErrorCode.ORDER_REFUND_NOT_ALLOWED);
        }

        Set<UUID> selectedIds = Set.copyOf(orderProductIds);
        if (selectedIds.size() != orderProductIds.size()) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }

        List<OrderProduct> selectedProducts = findOrderProducts(orderProductIds);
        if (selectedProducts.stream().anyMatch(product -> !product.isRefundable())) {
            throw new OrderException(ErrorCode.ORDER_REFUND_NOT_ALLOWED);
        }

        selectedProducts.forEach(OrderProduct::requestRefund);
        validateTransition(OrderStatus.REFUND_REQUESTED);
        this.orderStatus = OrderStatus.REFUND_REQUESTED;
    }

    public List<OrderProduct> completeRequestedRefund(int refundAmount, LocalDateTime refundedAt) {
        List<OrderProduct> selectedProducts = requestedRefundProducts();
        if (this.orderStatus != OrderStatus.REFUND_REQUESTED) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        validateRequestedRefundAmount(refundAmount);

        selectedProducts.forEach(product -> product.completeRefund(refundedAt));
        recalculateRefundStatus(refundedAt);
        return List.copyOf(selectedProducts);
    }

    public void validateRequestedRefundAmount(int refundAmount) {
        int expectedAmount = requestedRefundProducts().stream()
            .mapToInt(OrderProduct::getProductAmount)
            .sum();
        if (expectedAmount != refundAmount) {
            throw new OrderException(ErrorCode.ORDER_REFUND_AMOUNT_MISMATCH);
        }
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

        markFailed(expiredAt);
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
        return (this.orderStatus == OrderStatus.COMPLETED
            || this.orderStatus == OrderStatus.PARTIAL_REFUNDED
            || this.orderStatus == OrderStatus.REFUND_REQUESTED)
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

    private List<OrderProduct> findOrderProducts(List<UUID> orderProductIds) {
        List<OrderProduct> selectedProducts = orderProductIds.stream()
            .map(id -> this.orderProducts.stream()
                .filter(product -> product.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND)))
            .toList();
        if (selectedProducts.isEmpty()) {
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return selectedProducts;
    }

    private List<OrderProduct> requestedRefundProducts() {
        List<OrderProduct> requestedProducts = this.orderProducts.stream()
            .filter(product -> product.getOrderStatus() == OrderProductStatus.REFUND_REQUESTED)
            .toList();
        if (requestedProducts.isEmpty()) {
            throw new OrderException(ErrorCode.ORDER_REFUND_REQUEST_NOT_FOUND);
        }
        return requestedProducts;
    }
}
