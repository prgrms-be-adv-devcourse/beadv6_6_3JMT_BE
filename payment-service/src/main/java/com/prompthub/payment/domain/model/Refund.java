package com.prompthub.payment.domain.model;

import com.prompthub.payment.domain.exception.InvalidRefundStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "refund", uniqueConstraints =
    @UniqueConstraint(name = "uk_refund_request_id", columnNames = {"refund_request_id"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class Refund {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
    private UUID paymentId;

    @Column(name = "refund_request_id", columnDefinition = "uuid", nullable = false)
    private UUID refundRequestId;

    @Column(name = "refund_amount", nullable = false)
    private int refundAmount;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Enumerated(STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private RefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private Refund(
        UUID id, UUID paymentId, UUID refundRequestId,
        int refundAmount, String reason,
        RefundStatus status, OffsetDateTime requestedAt, OffsetDateTime completedAt
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.refundRequestId = refundRequestId;
        this.refundAmount = refundAmount;
        this.reason = reason;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    public static Refund create(
        UUID paymentId, UUID refundRequestId,
        int refundAmount, String reason
    ) {
        return new Refund(
            UUID.randomUUID(), paymentId, refundRequestId,
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

    public void fail(String failureCode, String failureReason, OffsetDateTime failedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → FAILED", id, status);
        this.status = RefundStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.failedAt = failedAt;
    }
}
