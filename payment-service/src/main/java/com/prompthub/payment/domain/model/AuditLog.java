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

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    private AuditLog(
        UUID id, AuditEntityType entityType, UUID entityId, AuditEventType eventType,
        UUID actorId, String newStatus, String detail, OffsetDateTime occurredAt
    ) {
        this.id = id;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.newStatus = newStatus;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static AuditLog forPaymentApproved(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.PAYMENT, payment.getId(), AuditEventType.PAYMENT_APPROVED,
            payment.getUserId(), payment.getStatus().name(), null, payment.getApprovedAt()
        );
    }

    public static AuditLog forPaymentFailed(Payment payment) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.PAYMENT, payment.getId(), AuditEventType.PAYMENT_FAILED,
            payment.getUserId(), payment.getStatus().name(), payment.getFailureReason(), payment.getFailedAt()
        );
    }

    public static AuditLog forPaymentRefunded(Payment payment, Refund refund) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.REFUND, refund.getId(), AuditEventType.PAYMENT_REFUNDED,
            payment.getUserId(), refund.getStatus().name(), null, refund.getCompletedAt()
        );
    }

    public static AuditLog forPaymentRefundFailed(Payment payment, Refund refund, String failureReason) {
        return new AuditLog(
            UUID.randomUUID(), AuditEntityType.REFUND, refund.getId(), AuditEventType.PAYMENT_REFUND_FAILED,
            payment.getUserId(), refund.getStatus().name(), failureReason, refund.getFailedAt()
        );
    }
}
