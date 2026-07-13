package com.prompthub.paymentservice.domain.model;

import com.prompthub.paymentservice.domain.exception.InvalidRefundStateException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Slf4j
@Getter
@Entity
@Table(name = "refund")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class Refund {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
    private UUID paymentId;

    @Column(name = "order_product_id", columnDefinition = "uuid", nullable = false)
    private UUID orderProductId;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "refund_amount", nullable = false)
    private int refundAmount;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Enumerated(STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private RefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private Refund(
        UUID id, UUID paymentId, UUID orderProductId, UUID userId,
        int refundAmount, String reason,
        RefundStatus status, OffsetDateTime requestedAt, OffsetDateTime completedAt
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderProductId = orderProductId;
        this.userId = userId;
        this.refundAmount = refundAmount;
        this.reason = reason;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    public static Refund create(
        UUID paymentId, UUID userId,
        int refundAmount, String reason,
        UUID orderProductId
    ) {
        return new Refund(
            UUID.randomUUID(), paymentId, orderProductId, userId,
            refundAmount, reason,
            RefundStatus.REQUESTED,
            OffsetDateTime.now(),
            null
        );
    }

    public void complete(OffsetDateTime completedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 COMPLETED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → COMPLETED", id, status);
        this.status = RefundStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail() {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → FAILED", id, status);
        this.status = RefundStatus.FAILED;
    }
}
