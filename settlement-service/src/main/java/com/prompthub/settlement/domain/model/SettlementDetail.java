package com.prompthub.settlement.domain.model;

import com.prompthub.settlement.domain.model.enums.SettlementLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "settlement_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDetail {

    private static final int AMOUNT_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "settlement_detail_id")
    private UUID id;

    @Column(name = "order_product_id")
    private UUID orderProductId;

    @Column(name = "line_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "line_settlement_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineSettlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false)
    private SettlementLineType lineType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static SettlementDetail sale(UUID orderProductId, BigDecimal lineAmount, BigDecimal feeRate,
                                        LocalDateTime occurredAt) {
        return create(orderProductId, lineAmount, feeRate, SettlementLineType.SALE, occurredAt);
    }

    public static SettlementDetail refund(UUID orderProductId, BigDecimal lineAmount, BigDecimal feeRate,
                                          LocalDateTime occurredAt) {
        return create(orderProductId, lineAmount.negate(), feeRate, SettlementLineType.REFUND, occurredAt);
    }

    private static SettlementDetail create(UUID orderProductId, BigDecimal lineAmount, BigDecimal feeRate,
                                           SettlementLineType lineType, LocalDateTime occurredAt) {
        SettlementDetail detail = new SettlementDetail();
        detail.orderProductId = orderProductId;
        detail.lineAmount = lineAmount;
        detail.feeRate = feeRate;
        detail.feeAmount = lineAmount.multiply(feeRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        detail.lineSettlementAmount = lineAmount.subtract(detail.feeAmount);
        detail.lineType = lineType;
        detail.occurredAt = occurredAt;
        detail.createdAt = LocalDateTime.now();
        return detail;
    }
}
