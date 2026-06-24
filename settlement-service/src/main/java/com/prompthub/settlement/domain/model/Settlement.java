package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.domain.exception.SettlementAlreadyCancelledException;
import com.prompthub.settlement.domain.exception.SettlementAlreadyPaidException;
import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.global.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "settlement_id")
    private UUID id;

    @Column(name = "settlement_batch_id")
    private UUID settlementBatchId;

    @Column(name = "seller_id", nullable = false)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false)
    private SettlementStatus settlementStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", nullable = false)
    private PayoutStatus payoutStatus;

    @Column(name = "failed_reason", length = 1000)
    private String failedReason;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "payout_reference", length = 100)
    private String payoutReference;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "settlement_id", nullable = false)
    private List<SettlementDetail> details = new ArrayList<>();

    public static Settlement create(UUID settlementBatchId, UUID sellerId, YearMonth period,
                                    List<SettlementDetail> details) {
        return new Settlement(settlementBatchId, sellerId, period, details);
    }

    private Settlement(UUID settlementBatchId, UUID sellerId, YearMonth period,
                       List<SettlementDetail> details) {
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(period, "정산 기간은 필수입니다.");
        Objects.requireNonNull(details, "정산 상세는 필수입니다.");
        this.settlementBatchId = settlementBatchId;
        this.sellerId = sellerId;
        this.periodStart = period.atDay(1);
        this.periodEnd = period.atEndOfMonth();
        this.details.addAll(details);
        this.productCount = details.size();
        this.totalAmount = sum(details, SettlementDetail::getLineAmount);
        this.feeTotalAmount = sum(details, SettlementDetail::getFeeAmount);
        this.settlementTotalAmount = sum(details, SettlementDetail::getLineSettlementAmount);
        this.refundAmount = BigDecimal.ZERO;
        this.settlementStatus = SettlementStatus.PENDING_APPROVAL;
        this.payoutStatus = PayoutStatus.NOT_READY;
        this.calculatedAt = LocalDateTime.now();
    }

    private static BigDecimal sum(List<SettlementDetail> details, Function<SettlementDetail, BigDecimal> field) {
        return details.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void approve(LocalDateTime confirmedAt) {
        if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
            throw new SettlementInvalidStateException("approve", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.APPROVED;
        this.payoutStatus = PayoutStatus.READY;
        this.confirmedAt = confirmedAt;
    }

    public void hold() {
        if (this.settlementStatus != SettlementStatus.PENDING_APPROVAL) {
            throw new SettlementInvalidStateException("hold", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.SETTLEMENT_ON_HOLD;
    }

    public void releaseHold() {
        if (this.settlementStatus != SettlementStatus.SETTLEMENT_ON_HOLD) {
            throw new SettlementInvalidStateException("releaseHold", this.settlementStatus, this.payoutStatus);
        }
        this.settlementStatus = SettlementStatus.PENDING_APPROVAL;
    }

    public void payout(LocalDateTime paidAt) {
        if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.READY) {
            throw new SettlementInvalidStateException("payout", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.PAID;
        this.paidAt = paidAt;
    }

    public void payoutHold() {
        if (this.settlementStatus != SettlementStatus.APPROVED || this.payoutStatus != PayoutStatus.READY) {
            throw new SettlementInvalidStateException("payoutHold", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.PAYOUT_ON_HOLD;
    }

    public void releasePayoutHold() {
        if (this.settlementStatus != SettlementStatus.APPROVED
                || this.payoutStatus != PayoutStatus.PAYOUT_ON_HOLD) {
            throw new SettlementInvalidStateException("releasePayoutHold", this.settlementStatus, this.payoutStatus);
        }
        this.payoutStatus = PayoutStatus.READY;
    }

    public SettlementDisplayStatus displayStatus() {
        return SettlementDisplayStatus.from(this.settlementStatus, this.payoutStatus);
    }

    public void cancel(LocalDateTime canceledAt) {
        if (this.payoutStatus == PayoutStatus.PAID) {
            throw new SettlementAlreadyPaidException(this.id);
        }
        if (this.settlementStatus == SettlementStatus.CANCELLED) {
            throw new SettlementAlreadyCancelledException(this.id);
        }
        this.settlementStatus = SettlementStatus.CANCELLED;
        this.canceledAt = canceledAt;
    }
}
