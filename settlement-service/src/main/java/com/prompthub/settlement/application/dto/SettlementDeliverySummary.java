package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import java.util.Map;

public record SettlementDeliverySummary(
        long total,
        long calculated,
        long reconciled,
        long deliveryFailed,
        long mismatch) {

    public static SettlementDeliverySummary from(
            Map<SettlementDeliveryStatus, Long> counts) {
        long calculated = count(counts, SettlementDeliveryStatus.CALCULATED);
        long reconciled = count(counts, SettlementDeliveryStatus.RECONCILED);
        long deliveryFailed =
                count(counts, SettlementDeliveryStatus.DELIVERY_FAILED);
        long mismatch = count(counts, SettlementDeliveryStatus.MISMATCH);
        return new SettlementDeliverySummary(
                calculated + reconciled + deliveryFailed + mismatch,
                calculated,
                reconciled,
                deliveryFailed,
                mismatch);
    }

    private static long count(
            Map<SettlementDeliveryStatus, Long> counts,
            SettlementDeliveryStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}
