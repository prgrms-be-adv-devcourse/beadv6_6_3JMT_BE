package com.prompthub.settlement.application.dto.delivery;

public record SettlementDeliveryComparison(
        boolean matched, String reason, int mismatchCount) {

    public static SettlementDeliveryComparison success() {
        return new SettlementDeliveryComparison(true, null, 0);
    }

    public static SettlementDeliveryComparison mismatched(
            String firstMismatch, int count) {
        return new SettlementDeliveryComparison(
                false, firstMismatch + ", mismatchCount=" + count, count);
    }
}
