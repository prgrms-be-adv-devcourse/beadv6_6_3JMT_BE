package com.prompthub.user.sellersettlement.domain.model;

import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementInvalidStateException;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "seller_settlement")
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerSettlement {

    @Id
    @Column(name = "seller_settlement_id", columnDefinition = "uuid")
    private UUID sellerSettlementId;

    @Column(name = "settlement_id", columnDefinition = "uuid", nullable = false, unique = true)
    private UUID settlementId;

    @Column(name = "seller_id", columnDefinition = "uuid", nullable = false)
    private UUID sellerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "product_count", nullable = false)
    private int productCount;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "settlement_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal settlementTotalAmount;

    @Column(name = "fee_total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal feeTotalAmount;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SettlementDisplayStatus status;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "payout_requested_at")
    private LocalDateTime payoutRequestedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static SellerSettlement seed(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt) {
        return new SellerSettlement(settlementId, sellerId, periodStart, periodEnd, productCount,
                totalAmount, settlementTotalAmount, feeTotalAmount, refundAmount, calculatedAt);
    }

    private SellerSettlement(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt) {
        this.sellerSettlementId = UUID.randomUUID();
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.productCount = productCount;
        this.totalAmount = totalAmount;
        this.settlementTotalAmount = settlementTotalAmount;
        this.feeTotalAmount = feeTotalAmount;
        this.refundAmount = refundAmount;
        this.calculatedAt = calculatedAt;
        this.status = SettlementDisplayStatus.WAITING;
    }

    public void approve() {
        requireStatus(SettlementDisplayStatus.WAITING);
        this.status = SettlementDisplayStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
    }

    public void hold() {
        requireStatus(SettlementDisplayStatus.WAITING);
        this.status = SettlementDisplayStatus.APPROVAL_ON_HOLD;
    }

    public void releaseHold() {
        requireStatus(SettlementDisplayStatus.APPROVAL_ON_HOLD);
        this.status = SettlementDisplayStatus.WAITING;
    }

    public void requestPayout() {
        requireStatus(SettlementDisplayStatus.APPROVED);
        this.status = SettlementDisplayStatus.PAYOUT_REQUESTED;
        this.payoutRequestedAt = LocalDateTime.now();
    }

    public void payout() {
        requireStatus(SettlementDisplayStatus.PAYOUT_REQUESTED);
        this.status = SettlementDisplayStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    public void payoutHold() {
        requireStatus(SettlementDisplayStatus.PAYOUT_REQUESTED);
        this.status = SettlementDisplayStatus.PAYOUT_ON_HOLD;
    }

    public void releasePayoutHold() {
        requireStatus(SettlementDisplayStatus.PAYOUT_ON_HOLD);
        this.status = SettlementDisplayStatus.PAYOUT_REQUESTED;
    }

    public void cancel() {
        if (this.status == SettlementDisplayStatus.PAID || this.status == SettlementDisplayStatus.CANCELLED) {
            throw new SellerSettlementInvalidStateException();
        }
        this.status = SettlementDisplayStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public boolean canRequestPayout() {
        return this.status == SettlementDisplayStatus.APPROVED;
    }

    private void requireStatus(SettlementDisplayStatus expected) {
        if (this.status != expected) {
            throw new SellerSettlementInvalidStateException();
        }
    }
}
