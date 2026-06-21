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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "payment")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class Payment {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", columnDefinition = "uuid", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "pg_tx_id", length = 100, nullable = false)
    private String pgTxId;

    @Enumerated(STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private PaymentStatus status;

    @Column(name = "payment_method", length = 30, nullable = false)
    private String paymentMethod;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "is_test", nullable = false)
    private boolean isTest;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "product_amount", nullable = false)
    private int productAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    @Column(name = "approved_amount")
    private Integer approvedAmount;

    @Column(name = "canceled_amount", nullable = false)
    private int canceledAmount;

    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private String requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private Payment(
        UUID id, UUID orderId, UUID userId,
        String pgTxId, PaymentStatus status,
        String paymentMethod, String provider, boolean isTest,
        int totalAmount, int productAmount, int discountAmount,
        Integer approvedAmount, int canceledAmount,
        String idempotencyKey
    ) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.pgTxId = pgTxId;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.provider = provider;
        this.isTest = isTest;
        this.totalAmount = totalAmount;
        this.productAmount = productAmount;
        this.discountAmount = discountAmount;
        this.approvedAmount = approvedAmount;
        this.canceledAmount = canceledAmount;
        this.idempotencyKey = idempotencyKey;
    }

    public static Payment create(
        UUID orderId, UUID userId,
        String pgTxId, String provider, String paymentMethod, boolean isTest,
        int productAmount, int discountAmount
    ) {
        return new Payment(
            UUID.randomUUID(), orderId, userId,
            pgTxId, PaymentStatus.READY,
            paymentMethod, provider, isTest,
            productAmount - discountAmount,
            productAmount, discountAmount,
            null,
            0,
            "pay-" + orderId
        );
    }
}
