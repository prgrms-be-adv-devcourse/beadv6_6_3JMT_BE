package com.prompthub.payment.domain.model;

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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "audit_log")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class AuditLog {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false)
    private UUID orderId;

    @Enumerated(STRING)
    @Column(name = "entity_type", columnDefinition = "varchar(20)", nullable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", columnDefinition = "uuid", nullable = false)
    private UUID entityId;

    @Enumerated(STRING)
    @Column(name = "event_type", columnDefinition = "varchar(30)", nullable = false)
    private AuditEventType eventType;

    @Column(name = "actor_id", columnDefinition = "uuid", nullable = false)
    private UUID actorId;

    @Column(name = "new_status", length = 20, nullable = false)
    private String newStatus;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    private AuditLog(
        UUID id, UUID orderId, AuditEntityType entityType, UUID entityId, AuditEventType eventType,
        UUID actorId, String newStatus, String failureCode, String detail, OffsetDateTime occurredAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.newStatus = newStatus;
        this.failureCode = failureCode;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static AuditLog forPaymentApproved(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.PAYMENT, payment.getId(),
            AuditEventType.PAYMENT_APPROVED, payment.getUserId(), payment.getStatus().name(),
            null, null, payment.getApprovedAt()
        );
    }

    public static AuditLog forPaymentFailed(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.PAYMENT, payment.getId(),
            AuditEventType.PAYMENT_FAILED, payment.getUserId(), payment.getStatus().name(),
            payment.getFailureCode(), payment.getFailureReason(), payment.getFailedAt()
        );
    }

    public static AuditLog forRefundRequested(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_REQUESTED, payment.getUserId(), RefundStatus.REQUESTED.name(),
            null, null, refund.getRequestedAt()
        );
    }

    public static AuditLog forRefundCompleted(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_COMPLETED, payment.getUserId(), refund.getStatus().name(),
            null, null, refund.getCompletedAt()
        );
    }

    public static AuditLog forRefundFailed(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), payment.getOrderId(), AuditEntityType.REFUND, refund.getId(),
            AuditEventType.REFUND_FAILED, payment.getUserId(), refund.getStatus().name(),
            refund.getFailureCode(), refund.getFailureReason(), refund.getFailedAt()
        );
    }
}
