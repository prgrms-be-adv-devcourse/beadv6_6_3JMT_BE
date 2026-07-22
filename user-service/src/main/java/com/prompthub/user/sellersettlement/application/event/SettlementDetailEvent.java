package com.prompthub.user.sellersettlement.application.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementDetailEvent(
        UUID settlementDetailId,
        UUID orderProductId,
        String lineType,
        BigDecimal lineAmount,
        BigDecimal feeRate,
        BigDecimal feeAmount,
        BigDecimal lineSettlementAmount,
        LocalDateTime occurredAt
) {

    public SettlementDetailEvent {
        Objects.requireNonNull(settlementDetailId, "settlementDetailId는 필수입니다.");
        Objects.requireNonNull(orderProductId, "orderProductId는 필수입니다.");
        Objects.requireNonNull(lineType, "lineType은 필수입니다.");
        Objects.requireNonNull(lineAmount, "lineAmount는 필수입니다.");
        Objects.requireNonNull(feeRate, "feeRate는 필수입니다.");
        Objects.requireNonNull(feeAmount, "feeAmount는 필수입니다.");
        Objects.requireNonNull(lineSettlementAmount, "lineSettlementAmount는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");

        if (!lineType.equals("SALE") && !lineType.equals("REFUND")) {
            throw new IllegalArgumentException("지원하지 않는 정산 Detail lineType입니다.");
        }
        validateSignedAmount(lineType, lineAmount, "lineAmount");
        validateSignedAmount(lineType, feeAmount, "feeAmount");
        validateSignedAmount(lineType, lineSettlementAmount, "lineSettlementAmount");
    }

    private static void validateSignedAmount(String lineType, BigDecimal amount, String fieldName) {
        int sign = amount.compareTo(BigDecimal.ZERO);
        if ((lineType.equals("SALE") && sign < 0) || (lineType.equals("REFUND") && sign > 0)) {
            throw new IllegalArgumentException(fieldName + "의 부호가 lineType과 일치하지 않습니다.");
        }
    }
}
