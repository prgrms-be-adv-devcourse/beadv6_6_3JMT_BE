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
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Slf4j
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

    @Column(name = "pg_tx_id", length = 255, nullable = false)
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

    @Column(name = "approved_amount")
    private Integer approvedAmount;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

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
        int totalAmount,
        Integer approvedAmount
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
        this.approvedAmount = approvedAmount;
    }

    // pgTxId(=Toss paymentKey)가 멱등키 역할을 겸한다(pg_tx_id UNIQUE). 별도 idempotency_key 컬럼 없음(D8).
    public static Payment create(
        UUID orderId, UUID userId,
        String pgTxId, String provider, String paymentMethod, boolean isTest,
        int totalAmount
    ) {
        return new Payment(
            UUID.randomUUID(), orderId, userId,
            pgTxId, PaymentStatus.READY,
            paymentMethod, provider, isTest,
            totalAmount,
            null
        );
    }

    public void markRequested(OffsetDateTime requestedAt) {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("READY 상태에서만 REQUESTED로 전환할 수 있습니다.");
        }
        this.status = PaymentStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }

    public void approve(int approvedAmount, String paymentMethod, String responsePayload, OffsetDateTime approvedAt) {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException("REQUESTED 상태에서만 PAID로 전환할 수 있습니다.");
        }
        this.status = PaymentStatus.PAID;
        this.approvedAmount = approvedAmount;
        this.paymentMethod = paymentMethod;
        this.responsePayload = responsePayload;
        this.approvedAt = approvedAt;
    }

    public void fail(String failureCode, String failureReason, String requestPayload, String responsePayload, OffsetDateTime failedAt) {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.failedAt = failedAt;
    }

    public void applyRefund(OffsetDateTime refundedAt, boolean isFullyRefunded) {
        if (this.status != PaymentStatus.PAID && this.status != PaymentStatus.PARTIAL_REFUNDED) {
            throw new IllegalStateException("PAID/PARTIAL_REFUNDED 상태에서만 환불을 적용할 수 있습니다.");
        }
        PaymentStatus previous = this.status;
        this.status = isFullyRefunded ? PaymentStatus.ALL_REFUNDED : PaymentStatus.PARTIAL_REFUNDED;
        this.refundedAt = refundedAt;
        log.debug("Payment 상태 전이 — id={}, {} → {}", id, previous, this.status);
    }
}
