package com.prompthub.settlement.domain.model;

import com.prompthub.domain.model.BaseEntity;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
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

    @Column(name = "payout_reference", length = 100)
    private String payoutReference;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "settlement_id", nullable = false)
    private List<SettlementDetail> details = new ArrayList<>();

    public static Settlement create(UUID settlementBatchId, UUID sellerId, YearMonth period,
                                    List<SettlementDetail> details) {
        Settlement settlement = new Settlement();
        settlement.settlementBatchId = settlementBatchId;
        settlement.sellerId = sellerId;
        settlement.periodStart = period.atDay(1);
        settlement.periodEnd = period.atEndOfMonth();
        settlement.details = new ArrayList<>(details);
        settlement.productCount = details.size();
        settlement.totalAmount = sum(details, SettlementDetail::getLineAmount);
        settlement.feeTotalAmount = sum(details, SettlementDetail::getFeeAmount);
        settlement.settlementTotalAmount = sum(details, SettlementDetail::getLineSettlementAmount);
        settlement.refundAmount = BigDecimal.ZERO;
        settlement.settlementStatus = SettlementStatus.PENDING_APPROVAL;
        settlement.payoutStatus = PayoutStatus.NOT_READY;
        settlement.calculatedAt = LocalDateTime.now();
        return settlement;
    }

    private static BigDecimal sum(List<SettlementDetail> details, Function<SettlementDetail, BigDecimal> field) {
        return details.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
