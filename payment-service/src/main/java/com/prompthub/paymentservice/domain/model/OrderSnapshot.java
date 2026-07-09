package com.prompthub.paymentservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

/**
 * 결제 시 금액의 진실 공급원이 되는 주문 스냅샷. order-events 이벤트 또는 gRPC 폴백으로 확보한다.
 * order_id 기준 불변 데이터로, 충돌 시 무시(upsert ignore)한다.
 */
@Getter
@Entity
@Table(name = "order_snapshot")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class OrderSnapshot {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "buyer_id", columnDefinition = "uuid", nullable = false)
    private UUID buyerId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(STRING)
    @Column(name = "source", length = 10, nullable = false)
    private OrderSnapshotSource source;

    @Column(name = "order_created_at", nullable = false)
    private OffsetDateTime orderCreatedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private OrderSnapshot(
        UUID id, UUID orderId, UUID buyerId,
        int totalAmount, OrderSnapshotSource source, OffsetDateTime orderCreatedAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.buyerId = buyerId;
        this.totalAmount = totalAmount;
        this.source = source;
        this.orderCreatedAt = orderCreatedAt;
    }

    public static OrderSnapshot create(
        UUID orderId, UUID buyerId,
        int totalAmount, OrderSnapshotSource source, OffsetDateTime orderCreatedAt
    ) {
        return new OrderSnapshot(
            UUID.randomUUID(), orderId, buyerId,
            totalAmount, source, orderCreatedAt
        );
    }
}
