package com.prompthub.user.sellersettlement.domain.model;

import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seller_settlement_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerSettlementDetail {

    @Id
    @Column(name = "settlement_detail_id", columnDefinition = "uuid")
    private UUID settlementDetailId;

    @Column(name = "order_product_id", columnDefinition = "uuid", nullable = false)
    private UUID orderProductId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private SellerSettlementLineType lineType;

    @Column(name = "line_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "line_settlement_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineSettlementAmount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static SellerSettlementDetail seed(
            UUID settlementDetailId,
            UUID orderProductId,
            SellerSettlementLineType lineType,
            BigDecimal lineAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal lineSettlementAmount,
            LocalDateTime occurredAt) {
        return new SellerSettlementDetail(
                settlementDetailId, orderProductId, lineType, lineAmount, feeRate,
                feeAmount, lineSettlementAmount, occurredAt, LocalDateTime.now());
    }

    private SellerSettlementDetail(
            UUID settlementDetailId,
            UUID orderProductId,
            SellerSettlementLineType lineType,
            BigDecimal lineAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal lineSettlementAmount,
            LocalDateTime occurredAt,
            LocalDateTime createdAt) {
        this.settlementDetailId = Objects.requireNonNull(settlementDetailId);
        this.orderProductId = Objects.requireNonNull(orderProductId);
        this.lineType = Objects.requireNonNull(lineType);
        this.lineAmount = Objects.requireNonNull(lineAmount);
        this.feeRate = Objects.requireNonNull(feeRate);
        this.feeAmount = Objects.requireNonNull(feeAmount);
        this.lineSettlementAmount = Objects.requireNonNull(lineSettlementAmount);
        validateSignedAmount(this.lineType, this.lineAmount, "lineAmount");
        validateSignedAmount(this.lineType, this.feeAmount, "feeAmount");
        validateSignedAmount(this.lineType, this.lineSettlementAmount, "lineSettlementAmount");
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    private static void validateSignedAmount(
            SellerSettlementLineType lineType,
            BigDecimal amount,
            String fieldName
    ) {
        int sign = amount.compareTo(BigDecimal.ZERO);
        if ((lineType == SellerSettlementLineType.SALE && sign < 0)
                || (lineType == SellerSettlementLineType.REFUND && sign > 0)) {
            throw new IllegalArgumentException(fieldName + "의 부호가 lineType과 일치하지 않습니다.");
        }
    }
}
