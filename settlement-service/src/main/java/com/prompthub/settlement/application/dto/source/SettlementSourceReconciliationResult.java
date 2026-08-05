package com.prompthub.settlement.application.dto.source;

import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record SettlementSourceReconciliationResult(
        SettlementSourceAggregate orderAggregate,
        SettlementSourceAggregate sourceAggregate,
        boolean matched,
        String failureReason) {

    public static SettlementSourceReconciliationResult compare(
            SettlementSourceAggregate orderAggregate,
            SettlementSourceAggregate sourceAggregate) {
        List<String> mismatches = new ArrayList<>();
        addCountMismatch(
                mismatches,
                "PAID_COUNT",
                orderAggregate.paidCount(),
                sourceAggregate.paidCount());
        addAmountMismatch(
                mismatches,
                "PAID_AMOUNT",
                orderAggregate.paidAmount(),
                sourceAggregate.paidAmount());
        addCountMismatch(
                mismatches,
                "REFUND_COUNT",
                orderAggregate.refundCount(),
                sourceAggregate.refundCount());
        addAmountMismatch(
                mismatches,
                "REFUND_AMOUNT",
                orderAggregate.refundAmount(),
                sourceAggregate.refundAmount());
        return new SettlementSourceReconciliationResult(
                orderAggregate,
                sourceAggregate,
                mismatches.isEmpty(),
                mismatches.isEmpty() ? null : String.join("; ", mismatches));
    }

    private static void addCountMismatch(
            List<String> mismatches,
            String name,
            long orderValue,
            long sourceValue) {
        if (orderValue != sourceValue) {
            mismatches.add("%s(order=%d, source=%d)".formatted(
                    name,
                    orderValue,
                    sourceValue));
        }
    }

    private static void addAmountMismatch(
            List<String> mismatches,
            String name,
            BigDecimal orderValue,
            BigDecimal sourceValue) {
        if (orderValue.compareTo(sourceValue) != 0) {
            mismatches.add("%s(order=%s, source=%s)".formatted(
                    name,
                    format(orderValue),
                    format(sourceValue)));
        }
    }

    private static String format(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
