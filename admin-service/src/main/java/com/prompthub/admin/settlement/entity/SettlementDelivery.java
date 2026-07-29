package com.prompthub.admin.settlement.entity;

import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Settlement Service의 settlement_delivery 운영 조회 모델.
 * 어드민은 이 행을 변경하지 않고 조회와 재전송 대상 검증에만 사용한다.
 */
@Entity
@Table(name = "settlement_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDelivery {

    @Id
    @Column(name = "settlement_delivery_id")
    private UUID settlementDeliveryId;

    @Column(name = "delivery_request_id", nullable = false)
    private UUID deliveryRequestId;

    @Column(name = "settlement_id", nullable = false)
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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean canRetry() {
        return status == SettlementDeliveryStatus.DELIVERY_FAILED;
    }
}
