package com.prompthub.settlement.application.event;

import com.prompthub.settlement.domain.model.Settlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SettlementCreatedEvent(
        int payloadVersion,
        UUID settlementId,
        UUID sellerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int productCount,
        BigDecimal totalAmount,
        BigDecimal settlementTotalAmount,
        BigDecimal feeTotalAmount,
        BigDecimal refundAmount,
        LocalDateTime calculatedAt,
        List<SettlementDetailEvent> details
) {

    public static final int CURRENT_PAYLOAD_VERSION = 2;

    public SettlementCreatedEvent {
        if (payloadVersion != CURRENT_PAYLOAD_VERSION) {
            throw new IllegalArgumentException("payloadVersion은 2여야 합니다.");
        }
        Objects.requireNonNull(settlementId, "settlementId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(periodStart, "periodStart는 필수입니다.");
        Objects.requireNonNull(periodEnd, "periodEnd는 필수입니다.");
        Objects.requireNonNull(totalAmount, "totalAmount는 필수입니다.");
        Objects.requireNonNull(settlementTotalAmount, "settlementTotalAmount는 필수입니다.");
        Objects.requireNonNull(feeTotalAmount, "feeTotalAmount는 필수입니다.");
        Objects.requireNonNull(refundAmount, "refundAmount는 필수입니다.");
        Objects.requireNonNull(calculatedAt, "calculatedAt은 필수입니다.");
        details = List.copyOf(Objects.requireNonNull(details, "details는 필수입니다."));
    }

    public static SettlementCreatedEvent from(Settlement settlement) {
        return new SettlementCreatedEvent(
                CURRENT_PAYLOAD_VERSION,
                settlement.getId(),
                settlement.getSellerId(),
                settlement.getPeriodStart(),
                settlement.getPeriodEnd(),
                settlement.getProductCount(),
                settlement.getTotalAmount(),
                settlement.getSettlementTotalAmount(),
                settlement.getFeeTotalAmount(),
                settlement.getRefundAmount(),
                settlement.getCalculatedAt(),
                settlement.getDetails().stream()
                        .map(SettlementDetailEvent::from)
                        .toList()
        );
    }
}
