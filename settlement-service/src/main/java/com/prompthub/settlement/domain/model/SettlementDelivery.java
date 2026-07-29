package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import com.prompthub.settlement.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDelivery extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "settlement_delivery_id")
    private UUID id;

    @Column(name = "delivery_request_id", nullable = false, unique = true)
    private UUID deliveryRequestId;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private UUID settlementId;

    @Column(name = "settlement_batch_id", nullable = false)
    private UUID settlementBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SettlementDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    @Column(name = "first_attempt_at")
    private LocalDateTime firstAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    public static SettlementDelivery calculated(
            UUID settlementId,
            UUID settlementBatchId,
            UUID deliveryRequestId) {
        SettlementDelivery delivery = new SettlementDelivery();
        delivery.settlementId = Objects.requireNonNull(settlementId);
        delivery.settlementBatchId = Objects.requireNonNull(settlementBatchId);
        delivery.deliveryRequestId = Objects.requireNonNull(deliveryRequestId);
        delivery.status = SettlementDeliveryStatus.CALCULATED;
        return delivery;
    }

    public void recordAttempt(LocalDateTime attemptedAt) {
        requireCalculated();
        LocalDateTime time = Objects.requireNonNull(attemptedAt);
        if (firstAttemptAt == null) {
            firstAttemptAt = time;
        }
        lastAttemptAt = time;
        attemptCount++;
    }

    public void reconcile(LocalDateTime completedAt) {
        requireCalculated();
        status = SettlementDeliveryStatus.RECONCILED;
        statusReason = null;
        reconciledAt = Objects.requireNonNull(completedAt);
    }

    public void fail(String reason) {
        requireCalculated();
        status = SettlementDeliveryStatus.DELIVERY_FAILED;
        statusReason = requireReason(reason);
    }

    public void mismatch(String reason) {
        requireCalculated();
        status = SettlementDeliveryStatus.MISMATCH;
        statusReason = requireReason(reason);
    }

    private void requireCalculated() {
        if (status != SettlementDeliveryStatus.CALCULATED) {
            throw new IllegalStateException("CALCULATED Delivery만 변경할 수 있습니다.");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("상태 사유는 필수입니다.");
        }
        return reason;
    }
}
