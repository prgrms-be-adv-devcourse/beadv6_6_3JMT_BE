package com.prompthub.user.sellersettlement.domain.model;

import com.prompthub.user.global.common.BaseEntity;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementInvalidStateException;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "seller_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerSettlement extends BaseEntity implements Persistable<UUID> {

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

    @Column(name = "payload_version", nullable = false)
    private short payloadVersion;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "seller_settlement_id", nullable = false)
    private List<SellerSettlementDetail> details = new ArrayList<>();

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

    @Transient
    private boolean newEntity = true;

    @Override
    public UUID getId() {
        return sellerSettlementId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public static SellerSettlement seed(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt) {
        return seedV1(settlementId, sellerId, periodStart, periodEnd, productCount,
                totalAmount, settlementTotalAmount, feeTotalAmount, refundAmount, calculatedAt);
    }

    public static SellerSettlement seedV1(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt) {
        return new SellerSettlement(settlementId, sellerId, periodStart, periodEnd, productCount,
                totalAmount, settlementTotalAmount, feeTotalAmount, refundAmount, calculatedAt,
                (short) 1, List.of());
    }

    public static SellerSettlement seedV2(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt,
            List<SellerSettlementDetail> details) {
        return new SellerSettlement(settlementId, sellerId, periodStart, periodEnd, productCount,
                totalAmount, settlementTotalAmount, feeTotalAmount, refundAmount, calculatedAt,
                (short) 2, details);
    }

    private SellerSettlement(UUID settlementId, UUID sellerId,
            LocalDate periodStart, LocalDate periodEnd, int productCount,
            BigDecimal totalAmount, BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount, BigDecimal refundAmount, LocalDateTime calculatedAt,
            short payloadVersion, List<SellerSettlementDetail> details) {
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
        this.payloadVersion = payloadVersion;
        this.details.addAll(details);
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

    @PostLoad
    @PostPersist
    private void markNotNew() {
        this.newEntity = false;
    }

    private void requireStatus(SettlementDisplayStatus expected) {
        if (this.status != expected) {
            throw new SellerSettlementInvalidStateException();
        }
    }
}
